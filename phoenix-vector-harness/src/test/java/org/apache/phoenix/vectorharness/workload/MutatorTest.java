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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.CorpusFactory;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.junit.Before;
import org.junit.Test;

public class MutatorTest {

  private final AtomicBoolean failCommit = new AtomicBoolean();
  private final AtomicBoolean failStatement = new AtomicBoolean();
  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();

  private HarnessConfig config;
  private Corpus corpus;
  private LiveCorpus live;
  private RowWriter writer;

  /** A connection that buffers nothing and fails its statements or commits on demand. */
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
      // The commit may have reached a table before it failed.
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
      // Nothing was sent.
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
      // The probe row may be in a table.
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
    // The probe row stays in flight, out of reach of the mutation workers, until it is deleted.
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
    // 100 mutations per second: number 6000 is one minute into the window, whenever it runs.
    assertEquals(1.0, m.minutes(6000), 1e-9);
    Thread.sleep(5);
    assertEquals(1.0, m.minutes(6000), 1e-9);
    // The next window starts its sequence again, after the configured length of the first.
    m.beginWindow(60_000_000_000L);
    assertEquals(1.5, m.minutes(0), 1e-9);
    assertEquals(2.5, m.minutes(6000), 1e-9);
    m.beginWindow(60_000_000_000L);
    assertEquals(2.5, m.minutes(0), 1e-9);
    // The kind of a sequence number does not depend on when it is chosen.
    Mutator other = mutator();
    other.beginWindow(90_000_000_000L);
    other.beginWindow(60_000_000_000L);
    other.beginWindow(60_000_000_000L);
    for (long seq = 0; seq < 200; seq++) {
      assertEquals(m.choose(seq), other.choose(seq));
    }
  }
}
