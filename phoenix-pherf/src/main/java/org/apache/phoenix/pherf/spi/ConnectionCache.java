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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides an opt-in connection cache layered in front of the registered connection provider.
 * Pherf.run() initializes this cache when the -connectionCache option is specified, using the
 * worker thread count as the cache capacity.
 * <p>
 * Each cached connection is keyed by its JDBC URL and its complete set of connection properties,
 * including the tenant id and any query set properties. Because Phoenix fixes these properties when
 * a connection is opened, a cached connection can serve only operations requesting the same tenant
 * and properties. Query sets and DDL operations with identical properties share connections.
 * <p>
 * Every lease is a new proxy instance wrapping a cached connection. Closing the lease resets the
 * underlying connection and returns it to the idle pool. The reset step closes all statements
 * created under the lease, rolls back active transactions when autoCommit is disabled, restores
 * autoCommit to the value it had when the connection was opened, and clears any warnings.
 * <p>
 * The cache closes and discards the underlying connection rather than retaining it under any of the
 * following conditions:
 * <ul>
 * <li>the connection is already closed, or its schema has changed;</li>
 * <li>the lease invoked a state setter other than setAutoCommit;</li>
 * <li>an invocation on the lease threw an exception (exceptions thrown directly by a statement do
 * not pass through the lease);</li>
 * <li>the reset step failed;</li>
 * <li>the cache has been finished.</li>
 * </ul>
 * <p>
 * Resetting a lease does not revert session state modified through SQL statements, such as ALTER
 * SESSION SET CONSISTENCY, nor does it undo changes applied directly to the underlying connection
 * returned by Statement.getConnection(). Any such state persists for the lifetime of the cached
 * connection.
 * <p>
 * The total number of idle connections across all keys is bounded by the configured capacity. When
 * a returned connection causes the idle count to exceed this limit, the oldest idle connection is
 * evicted and closed.
 */
public final class ConnectionCache implements ConnectionProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionCache.class);
  private static volatile ConnectionCache active;

  private final ConnectionProvider delegate;
  private final int capacity;
  // Holds the idle connections across all keys, ordered from oldest to newest.
  private final ArrayDeque<Cached> idle = new ArrayDeque<>();
  private boolean finished;

  private ConnectionCache(ConnectionProvider delegate, int capacity) {
    this.delegate = delegate;
    this.capacity = capacity;
  }

  /**
   * Initializes and activates the connection cache for a run in front of the registered connection
   * provider, or the default provider if none is registered.
   * @throws IllegalStateException when more than one provider is registered or a cache is already
   *                               active.
   */
  public static void begin(int capacity) {
    begin(Extensions.registeredConnectionProvider(), capacity);
  }

  static synchronized ConnectionCache begin(ConnectionProvider delegate, int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Connection cache capacity must be positive: " + capacity);
    }
    if (active != null) {
      throw new IllegalStateException("A connection cache is already active");
    }
    active = new ConnectionCache(delegate, capacity);
    return active;
  }

  /**
   * Deactivates the connection cache for the current run and closes all idle connections. Any lease
   * released after the cache has finished closes its underlying connection immediately. Connection
   * close errors are logged and not rethrown. If no cache is active, this method performs no
   * action.
   */
  public static void finish() {
    ConnectionCache cache;
    synchronized (ConnectionCache.class) {
      cache = active;
      active = null;
    }
    if (cache != null) {
      List<Cached> closing;
      synchronized (cache) {
        cache.finished = true;
        closing = new ArrayList<>(cache.idle);
        cache.idle.clear();
      }
      for (Cached cached : closing) {
        closeQuietly(cached);
      }
    }
  }

  /** Returns the currently active connection cache, or null if no cache is active. */
  static ConnectionCache active() {
    return active;
  }

  @Override
  public Connection getConnection(String url, Properties properties) throws SQLException {
    // Snapshot the connection properties into the cache key before passing them to the delegate.
    Map<String, String> sorted = new TreeMap<>();
    for (String name : properties.stringPropertyNames()) {
      sorted.put(name, properties.getProperty(name));
    }
    List<Object> key = Arrays.asList(url, sorted);
    Cached cached = takeIdle(key);
    if (cached != null) {
      return lease(cached);
    }
    Connection connection = delegate.getConnection(url, properties);
    try {
      return lease(new Cached(key, connection, connection.getAutoCommit(), connection.getSchema()));
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException | RuntimeException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  /** Removes and returns the most recently pooled idle connection matching the key, or null. */
  private synchronized Cached takeIdle(List<Object> key) {
    for (Iterator<Cached> it = idle.descendingIterator(); it.hasNext();) {
      Cached cached = it.next();
      if (cached.key.equals(key)) {
        it.remove();
        return cached;
      }
    }
    return null;
  }

  private Connection lease(Cached cached) {
    return (Connection) Proxy.newProxyInstance(ConnectionCache.class.getClassLoader(),
      new Class<?>[] { Connection.class }, new Lease(cached));
  }

  /**
   * Resets the underlying connection of a closed lease and either returns it to the idle pool or
   * closes it if it can no longer be reused.
   */
  private void release(Cached cached, List<Statement> statements, boolean reusable) {
    for (Statement statement : statements) {
      try {
        statement.close();
      } catch (SQLException | RuntimeException e) {
        reusable = false;
      }
    }
    Connection connection = cached.connection;
    try {
      reusable = reusable && !connection.isClosed();
      if (reusable) {
        boolean autoCommit = connection.getAutoCommit();
        if (!autoCommit) {
          connection.rollback();
        }
        if (autoCommit != cached.autoCommit) {
          connection.setAutoCommit(cached.autoCommit);
        }
        connection.clearWarnings();
        reusable = Objects.equals(cached.schema, connection.getSchema());
      }
    } catch (SQLException | RuntimeException e) {
      reusable = false;
    }
    Cached evicted = null;
    synchronized (this) {
      if (reusable && !finished) {
        idle.addLast(cached);
        cached = null;
        if (idle.size() > capacity) {
          evicted = idle.pollFirst();
        }
      }
    }
    closeQuietly(cached);
    closeQuietly(evicted);
  }

  private static void closeQuietly(Cached cached) {
    if (cached != null) {
      try {
        cached.connection.close();
      } catch (SQLException | RuntimeException e) {
        LOGGER.warn("Cannot close a cached connection", e);
      }
    }
  }

  /** Holds an underlying physical connection alongside its cache key and initial open state. */
  private static final class Cached {
    final List<Object> key;
    final Connection connection;
    final boolean autoCommit;
    final String schema;

    Cached(List<Object> key, Connection connection, boolean autoCommit, String schema) {
      this.key = key;
      this.connection = connection;
      this.autoCommit = autoCommit;
      this.schema = schema;
    }
  }

  /**
   * Represents a single lease over a cached connection and tracks all statements created during its
   * lifetime. Once closed, any method invocation other than close and isClosed throws an exception
   * so that a stale reference cannot interact with a connection leased to a subsequent caller.
   */
  private final class Lease implements InvocationHandler {
    private final Cached cached;
    private final List<Statement> statements = new ArrayList<>();
    private boolean closed;
    // Cleared when a state setter other than setAutoCommit is invoked or when a call throws an
    // exception, ensuring that the connection is closed rather than pooled upon release.
    private boolean reusable = true;

    Lease(Cached cached) {
      this.cached = cached;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      int arity = args == null ? 0 : args.length;
      if ("equals".equals(name) && arity == 1) {
        return proxy == args[0];
      }
      if ("hashCode".equals(name) && arity == 0) {
        return System.identityHashCode(proxy);
      }
      if ("toString".equals(name) && arity == 0) {
        return "ConnectionCache lease@" + Integer.toHexString(System.identityHashCode(proxy));
      }
      List<Statement> created;
      boolean reuse;
      synchronized (this) {
        if ("close".equals(name) && arity == 0) {
          if (closed) {
            return null;
          }
          closed = true;
          created = new ArrayList<>(statements);
          reuse = reusable;
        } else if ("isClosed".equals(name) && arity == 0 && closed) {
          return true;
        } else if (closed) {
          throw new SQLException("The connection lease is closed");
        } else {
          return call(method, args);
        }
      }
      release(cached, created, reuse);
      return null;
    }

    // Executes under the lease monitor so that only one invocation at a time reaches the underlying
    // physical connection.
    private Object call(Method method, Object[] args) throws Throwable {
      String name = method.getName();
      if (name.startsWith("set") && !"setAutoCommit".equals(name)) {
        reusable = false;
      }
      Object result;
      try {
        result = method.invoke(cached.connection, args);
      } catch (InvocationTargetException e) {
        reusable = false;
        throw e.getCause();
      }
      if (result instanceof Statement) {
        statements.add((Statement) result);
      }
      return result;
    }
  }
}
