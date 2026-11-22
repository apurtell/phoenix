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
package org.apache.phoenix.pherf.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Verifies the key, state reset, and capacity bound rules of the connection cache in process using
 * a counting provider that supplies fake connections.
 */
public class ConnectionCacheTest {
  private static final String URL = "jdbc:phoenix:cache";
  private static final int CAPACITY = 3;

  private final List<Fake> opened = new CopyOnWriteArrayList<>();
  private ConnectionCache cache;

  @Before
  public void begin() {
    cache = ConnectionCache.begin((url, properties) -> {
      Fake fake = new Fake(properties);
      opened.add(fake);
      return fake.connection;
    }, CAPACITY);
  }

  @After
  public void finish() {
    ConnectionCache.finish();
  }

  private static Properties properties(String... keysAndValues) {
    Properties properties = new Properties();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
    }
    return properties;
  }

  private Connection lease(String... keysAndValues) throws SQLException {
    return cache.getConnection(URL, properties(keysAndValues));
  }

  /**
   * Returns the underlying fake connection that a lease uses. Each fake connection reports its id
   * as its catalog.
   */
  private Fake real(Connection lease) throws SQLException {
    return opened.get(Integer.parseInt(lease.getCatalog()));
  }

  @Test
  public void testActiveBetweenBeginAndFinish() {
    assertSame(cache, Extensions.connectionProvider());
    try {
      ConnectionCache.begin(ConnectionProvider.DEFAULT, CAPACITY);
      fail("A second cache started");
    } catch (IllegalStateException expected) {
    }
    ConnectionCache.finish();
    assertSame(ConnectionProvider.DEFAULT, Extensions.connectionProvider());
    try {
      ConnectionCache.begin(ConnectionProvider.DEFAULT, 0);
      fail("A cache with no capacity started");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testSameKeyReusesOneConnection() throws SQLException {
    Connection first = lease("TenantId", "A");
    Fake fake = real(first);
    first.close();
    Connection second = lease("TenantId", "A");
    assertSame(fake, real(second));
    assertEquals(1, opened.size());
    // Each acquired lease is wrapped in its own distinct proxy instance.
    assertNotEquals(first, second);
    assertEquals(second, second);
    second.close();
  }

  @Test
  public void testTenantAndGroupGiveSeparateConnections() throws SQLException {
    String[][] keys =
      { { "TenantId", "A" }, { "TenantId", "B" }, { "TenantId", "A", "pherf.group", "G" } };
    for (String[] key : keys) {
      lease(key).close();
    }
    assertEquals(keys.length, opened.size());
    for (int i = 0; i < keys.length; i++) {
      Connection connection = lease(keys[i]);
      Fake fake = real(connection);
      assertSame(opened.get(i), fake);
      assertEquals(properties(keys[i]), fake.properties);
      connection.close();
    }
    assertEquals(keys.length, opened.size());
  }

  @Test
  public void testAutoCommitRestoredAndRollbackWhenOff() throws SQLException {
    Connection connection = lease();
    Fake fake = real(connection);
    connection.setAutoCommit(true);
    connection.close();
    assertFalse("autoCommit at open is restored", fake.autoCommit);
    assertEquals(0, fake.rollbacks);

    connection = lease();
    connection.close();
    assertEquals("autoCommit off rolls back", 1, fake.rollbacks);
    verify(fake.connection, Mockito.times(2)).clearWarnings();
    assertEquals(1, opened.size());
  }

  @Test
  public void testOpenStatementsCloseOnReturn() throws SQLException {
    Connection connection = lease();
    Statement statement = connection.createStatement();
    PreparedStatement prepared = connection.prepareStatement("SELECT 1");
    CallableStatement call = connection.prepareCall("CALL X()");
    connection.close();
    verify(statement).close();
    verify(prepared).close();
    verify(call).close();
    assertFalse(real(lease()).closed);
  }

  @Test
  public void testStateChangesCloseForReal() throws SQLException {
    List<String> cases = Arrays.asList("setReadOnly", "schema", "closed", "throw", "reset");
    for (String change : cases) {
      Connection connection = lease("case", change);
      Fake fake = real(connection);
      if ("setReadOnly".equals(change)) {
        connection.setReadOnly(true);
      } else if ("schema".equals(change)) {
        fake.schema = "OTHER";
      } else if ("closed".equals(change)) {
        fake.closed = true;
      } else if ("throw".equals(change)) {
        fake.failing = "getMetaData";
        try {
          connection.getMetaData();
          fail("No exception through the proxy");
        } catch (SQLException expected) {
        }
      } else {
        fake.failing = "rollback";
      }
      connection.close();
      assertTrue(change + " closes the connection", fake.closed);
      Connection next = lease("case", change);
      assertNotSame(change, fake, real(next));
      next.close();
    }
    assertEquals(2 * cases.size(), opened.size());
  }

  @Test
  public void testIdleBoundEvictsOldest() throws SQLException {
    List<Connection> leases = new ArrayList<>();
    for (int i = 0; i <= CAPACITY; i++) {
      leases.add(lease("key", String.valueOf(i)));
    }
    for (Connection connection : leases) {
      connection.close();
    }
    assertTrue("The oldest idle connection closes", opened.get(0).closed);
    for (int i = 1; i <= CAPACITY; i++) {
      assertFalse(opened.get(i).closed);
      Connection connection = lease("key", String.valueOf(i));
      assertSame(opened.get(i), real(connection));
      connection.close();
    }
    assertEquals(CAPACITY + 1, opened.size());
    lease("key", "0").close();
    assertEquals(CAPACITY + 2, opened.size());
  }

  @Test
  public void testFinishClosesIdleAndLaterReturns() throws SQLException {
    Connection idle = lease("key", "idle");
    Connection held = lease("key", "held");
    Fake idleFake = real(idle);
    Fake heldFake = real(held);
    idle.close();
    ConnectionCache.finish();
    assertTrue(idleFake.closed);
    assertFalse(heldFake.closed);
    held.close();
    assertTrue(heldFake.closed);
  }

  @Test
  public void testStaleLeaseThrowsAndCloseIsIdempotent() throws SQLException {
    Connection connection = lease();
    connection.close();
    connection.close();
    assertTrue(connection.isClosed());
    try {
      connection.createStatement();
      fail("A closed lease made a statement");
    } catch (SQLException expected) {
    }
    // A double close returns the underlying connection to the cache only once, so holding two
    // active leases at a time still requires two separate connections.
    Connection first = lease();
    Connection second = lease();
    assertNotSame(real(first), real(second));
    assertEquals(2, opened.size());
    first.close();
    second.close();
  }

  @Test
  public void testConcurrentLeasesBoundedByCapacity() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CAPACITY);
    try {
      List<Future<?>> threads = new ArrayList<>();
      for (int t = 0; t < CAPACITY; t++) {
        threads.add(pool.submit(() -> {
          for (int i = 0; i < 200; i++) {
            Connection connection = lease("TenantId", "A");
            connection.setAutoCommit(true);
            connection.prepareStatement("SELECT 1");
            connection.close();
          }
          return null;
        }));
      }
      for (Future<?> thread : threads) {
        thread.get();
      }
    } finally {
      pool.shutdown();
    }
    assertTrue("Opens: " + opened.size(), opened.size() <= CAPACITY);
  }

  /**
   * Represents a fake underlying real connection. Its initial state at open has autoCommit turned
   * off and no schema configured.
   */
  private final class Fake implements Answer<Object> {
    final Properties properties;
    final Connection connection;
    final String id;
    volatile boolean autoCommit;
    volatile String schema;
    volatile boolean closed;
    volatile String failing;
    int rollbacks;

    Fake(Properties properties) {
      this.properties = (Properties) properties.clone();
      this.id = String.valueOf(opened.size());
      this.connection = Mockito.mock(Connection.class, Mockito.withSettings().defaultAnswer(this));
    }

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
      String name = invocation.getMethod().getName();
      if (name.equals(failing)) {
        throw new SQLException(name + " failed");
      }
      switch (name) {
        case "getCatalog":
          return id;
        case "getAutoCommit":
          return autoCommit;
        case "setAutoCommit":
          autoCommit = invocation.getArgument(0);
          return null;
        case "getSchema":
          return schema;
        case "isClosed":
          return closed;
        case "close":
          closed = true;
          return null;
        case "rollback":
          rollbacks++;
          return null;
        case "createStatement":
          return Mockito.mock(Statement.class);
        case "prepareStatement":
          return Mockito.mock(PreparedStatement.class);
        case "prepareCall":
          return Mockito.mock(CallableStatement.class);
        default:
          return Mockito.RETURNS_DEFAULTS.answer(invocation);
      }
    }
  }
}
