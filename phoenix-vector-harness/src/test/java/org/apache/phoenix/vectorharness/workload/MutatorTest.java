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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.CorpusFactory;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.corpus.VecsFile;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MutatorTest {

  /** Rows of the published test corpus. The last {@link #HELD_OUT} rows supply new vectors. */
  private static final int BASE = 400;
  private static final int HELD_OUT = 150;
  private static final long WINDOW_NANOS = 60_000_000_000L;
  /** Throttle slots of one window at one operation per second. */
  private static final int SLOTS = 61;

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private final AtomicBoolean failCommit = new AtomicBoolean();
  private final AtomicBoolean failStatement = new AtomicBoolean();
  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();

  private HarnessConfig config;
  private Corpus corpus;
  private LiveCorpus live;
  private RowWriter writer;
  private List<float[]> base;

  /** Mock Connection proxy that can simulate statement or commit failures. */
  private Connection connection() {
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
      new Class<?>[] { Connection.class }, (proxy, method, args) -> {
        switch (method.getName()) {
          case "prepareStatement":
            return statement();
          case "commit":
            commits.incrementAndGet();
            if (failCommit.get()) {
              throw new SQLException("commit failed after the first table");
            }
            return null;
          case "rollback":
            rollbacks.incrementAndGet();
            return null;
          default:
            return method.getReturnType() == boolean.class ? false : null;
        }
      });
  }

  private PreparedStatement statement() {
    return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
      new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
        if (method.getName().equals("executeUpdate")) {
          if (failStatement.get()) {
            throw new SQLException("statement failed");
          }
          return 1;
        }
        Class<?> rt = method.getReturnType();
        return rt == int.class ? 0 : rt == boolean.class ? false : null;
      });
  }

  @Before
  public void setUp() throws Exception {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "200");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    p.setProperty("mutation.rate.per_sec", "100");
    config = HarnessConfig.of("t", p);
    corpus = CorpusFactory.open(config);
    live = new LiveCorpus(corpus, corpus.rows());
    writer =
      new RowWriter(connection(), TableNames.of(config), config.layouts(), corpus.attributeModel());
  }

  private Mutator mutator() {
    return new Mutator(config, corpus, live, null, new WriteGate(), null);
  }

  @Test
  public void testFailedCommitLeavesTheRowUnresolvedAndInFlight() throws Exception {
    Mutator m = mutator();
    failCommit.set(true);
    try {
      m.apply(writer, Mutator.Kind.DELETE, 3);
      fail("Expected the commit to fail");
    } catch (SQLException expected) {
      // Commit failure leaves row state potentially partially applied
    }
    assertEquals(1, m.unresolved().size());
    long o = m.unresolved().iterator().next();
    assertTrue(o < corpus.rows());
    assertTrue("the record of the row is unchanged", live.isLive(o));
    assertTrue("later writes and online scoring stay away", live.writeInFlight(o));
    assertEquals(1, rollbacks.get());
    assertEquals(1L, m.counts().get("failed"));
    assertEquals(0L, m.counts().get("delete"));
    m.resolve(o);
    assertTrue(m.unresolved().isEmpty());
    assertFalse(live.writeInFlight(o));
  }

  @Test
  public void testFailedStatementIsRolledBackAndNotUnresolved() throws Exception {
    Mutator m = mutator();
    failStatement.set(true);
    try {
      m.apply(writer, Mutator.Kind.INSERT, 5);
      fail("Expected the statement to fail");
    } catch (SQLException expected) {
      // Statement failure aborted execution before commit
    }
    assertEquals("the buffered statements of the other layout are discarded", 1, rollbacks.get());
    assertEquals(0, commits.get());
    assertTrue(m.unresolved().isEmpty());
    assertEquals(0, live.inFlightCount());
    assertEquals(1L, m.counts().get("failed"));
  }

  @Test
  public void testFailedProbeCommitIsUnresolved() throws Exception {
    Mutator m = mutator();
    failCommit.set(true);
    try {
      m.insertProbe(writer, corpus.vector(0));
      fail("Expected the commit to fail");
    } catch (SQLException expected) {
      // Probe row may have been committed prior to failure
    }
    assertEquals(1, m.unresolved().size());
    long o = m.unresolved().iterator().next();
    assertTrue("an inserted ordinal", o >= corpus.rows());
    assertEquals(1L, m.counts().get("probe_failed"));
    assertEquals(1, rollbacks.get());
  }

  @Test
  public void testProbeRowsAreCountedApartFromMutations() throws Exception {
    Mutator m = mutator();
    long o = m.insertProbe(writer, corpus.vector(0));
    assertTrue(live.isLive(o));
    // Probe row remains marked in-flight to prevent mutation access until deleted
    assertTrue(live.writeInFlight(o));
    m.deleteProbe(writer, o);
    assertFalse(live.isLive(o));
    Map<String, Object> counts = m.counts();
    assertEquals(0L, counts.get("insert"));
    assertEquals(0L, counts.get("delete"));
    assertEquals(1L, counts.get("probe_inserts"));
    assertEquals(1L, counts.get("probe_deletes"));
    assertTrue(m.unresolved().isEmpty());
    assertEquals(0, live.inFlightCount());
  }

  @Test
  public void testDriftClockFollowsTheScheduleOfTheWindows() throws Exception {
    Mutator m = mutator();
    m.beginWindow(90_000_000_000L);
    assertEquals(0.0, m.minutes(0), 0.0);
    // At 100 mutations/sec, sequence 6000 represents 60 seconds (1.0 minute)
    assertEquals(1.0, m.minutes(6000), 1e-9);
    Thread.sleep(5);
    assertEquals(1.0, m.minutes(6000), 1e-9);
    // Subsequent window offsets clock by elapsed duration of preceding window
    m.beginWindow(60_000_000_000L);
    assertEquals(1.5, m.minutes(0), 1e-9);
    assertEquals(2.5, m.minutes(6000), 1e-9);
    m.beginWindow(60_000_000_000L);
    assertEquals(2.5, m.minutes(0), 1e-9);
    // Mutation kind selection is deterministic with respect to sequence number
    Mutator other = mutator();
    other.beginWindow(90_000_000_000L);
    other.beginWindow(60_000_000_000L);
    other.beginWindow(60_000_000_000L);
    for (long seq = 0; seq < 200; seq++) {
      assertEquals(m.choose(seq), other.choose(seq));
    }
  }

  /** Writes a small texmex corpus and returns a configuration with two steady windows. */
  private HarnessConfig published() throws Exception {
    Path dir = tmp.newFolder().toPath();
    base = new ArrayList<>();
    SplitMix64 rnd = new SplitMix64(9L);
    for (int i = 0; i < BASE; i++) {
      float[] v = new float[4];
      for (int j = 0; j < v.length; j++) {
        v[j] = (float) rnd.nextGaussian();
      }
      base.add(v);
    }
    VecsFile.writeFvecs(dir.resolve("sift_base.fvecs"), base);
    VecsFile.writeFvecs(dir.resolve("sift_query.fvecs"), base.subList(0, 5));
    Properties p = new Properties();
    p.setProperty("corpus.source", "texmex");
    p.setProperty("corpus.path", dir.toString());
    p.setProperty("corpus.rows", Integer.toString(BASE));
    p.setProperty("corpus.holdout.rows", Integer.toString(HELD_OUT));
    p.setProperty("mutation.rate.per_sec", "1");
    p.setProperty("mutation.update.move_fraction", "0.5");
    p.setProperty("steady_state.warmup", "30s");
    p.setProperty("steady_state.duration", "30s");
    p.setProperty("query.rate.values", "10,20");
    HarnessConfig c = HarnessConfig.of("t", p);
    assertTrue(c.validate().toString(), c.validate().isEmpty());
    return c;
  }

  /**
   * Runs the operations of both windows with two threads in a shuffled order and records the draw
   * index of each (window, seq). A real run applies the operations in the order of thread timing.
   */
  private static Map<String, Long> draws(HarnessConfig c, Corpus published, long shuffleSeed)
    throws InterruptedException {
    Mutator m = new Mutator(c, published, new LiveCorpus(published, published.rows()), null,
      new WriteGate(), null);
    Map<String, Long> draws = new ConcurrentHashMap<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Random shuffle = new Random(shuffleSeed);
    for (int w = 0; w < c.queryRates().length; w++) {
      m.beginWindow(WINDOW_NANOS);
      List<Long> seqs = new ArrayList<>();
      for (long seq = 0; seq < SLOTS; seq++) {
        seqs.add(seq);
      }
      Collections.shuffle(seqs, shuffle);
      int window = w;
      CountDownLatch start = new CountDownLatch(1);
      List<Thread> threads = new ArrayList<>();
      List<List<Long>> halves =
        Arrays.asList(seqs.subList(0, seqs.size() / 2), seqs.subList(seqs.size() / 2, seqs.size()));
      for (List<Long> mine : halves) {
        Thread t = new Thread(() -> {
          try {
            start.await();
            for (long seq : mine) {
              Mutator.Kind kind = m.choose(seq);
              long d = m.draw(kind, seq);
              assertEquals(Mutator.writesNewVector(kind), d >= 0);
              if (d >= 0) {
                draws.put(window + ":" + seq, d);
              }
            }
          } catch (Throwable e) {
            error.compareAndSet(null, e);
          }
        });
        t.start();
        threads.add(t);
      }
      start.countDown();
      for (Thread t : threads) {
        t.join();
      }
      if (error.get() != null) {
        throw new AssertionError("a draw failed", error.get());
      }
    }
    return draws;
  }

  @Test
  public void testHeldOutRowsDependOnlyOnWindowAndSequenceNumber() throws Exception {
    HarnessConfig c = published();
    try (Corpus published = CorpusFactory.open(c)) {
      assertEquals(BASE - HELD_OUT, published.rows());
      Map<String, Long> a = draws(c, published, 1L);
      Map<String, Long> b = draws(c, published, 2L);
      assertTrue(a.toString(), a.size() > 30);
      // Paired runs map each operation to the same held-out row, whatever the thread timing.
      assertEquals(new TreeMap<>(a), new TreeMap<>(b));
      assertEquals("no two operations share a row", a.size(), new HashSet<>(a.values()).size());
      long perWindow = c.newVectorsPerWindow();
      assertEquals(c.plannedNewVectors(), 2 * perWindow);
      assertTrue(c.plannedNewVectors() <= HELD_OUT);
      for (Map.Entry<String, Long> e : a.entrySet()) {
        int w = Integer.parseInt(e.getKey().substring(0, e.getKey().indexOf(':')));
        assertTrue(e.toString(),
          e.getValue() >= w * perWindow && e.getValue() < (w + 1) * perWindow);
      }
    }
  }

  @Test
  public void testAppliedOperationsWriteTheirHeldOutRow() throws Exception {
    HarnessConfig c = published();
    try (Corpus published = CorpusFactory.open(c)) {
      LiveCorpus rows = new LiveCorpus(published, published.rows());
      Mutator m = new Mutator(c, published, rows, null, new WriteGate(), null);
      RowWriter w =
        new RowWriter(connection(), TableNames.of(c), c.layouts(), published.attributeModel());
      m.beginWindow(WINDOW_NANOS);
      m.beginWindow(WINDOW_NANOS);
      int checked = 0;
      for (long seq = 0; seq < SLOTS && checked < 6; seq++) {
        Mutator.Kind kind = m.choose(seq);
        if (!Mutator.writesNewVector(kind)) {
          continue;
        }
        long draw = m.draw(kind, seq);
        long ordinal = m.apply(w, kind, seq);
        assertTrue(ordinal >= 0);
        // The second window takes rows of its own range, after the rows of the first window.
        assertTrue(draw >= c.newVectorsPerWindow());
        float[] expected = base.get((int) (published.rows() + draw));
        assertArrayEquals(expected, rows.get(ordinal).vector(), 0.0f);
        checked++;
      }
      assertEquals(6, checked);
    }
  }

  @Test
  public void testMoreNewVectorsThanPlannedFailsClearly() throws Exception {
    HarnessConfig c = published();
    try (Corpus published = CorpusFactory.open(c)) {
      Mutator m = new Mutator(c, published, new LiveCorpus(published, published.rows()), null,
        new WriteGate(), null);
      try {
        m.draw(Mutator.Kind.INSERT, 0);
        fail("a draw before the first window must fail");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("no steady window"));
      }
      m.beginWindow(WINDOW_NANOS);
      // A throttle that runs far past its rate makes more operations than the window plans.
      try {
        for (long seq = 0; seq < 100_000; seq++) {
          Mutator.Kind kind = m.choose(seq);
          if (Mutator.writesNewVector(kind)) {
            assertTrue(m.draw(kind, seq) < c.newVectorsPerWindow());
          }
        }
        fail("a window past its planned new vectors must fail");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("planned new vectors"));
      }
    }
  }
}
