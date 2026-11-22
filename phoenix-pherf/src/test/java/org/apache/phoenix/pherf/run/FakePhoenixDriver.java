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
package org.apache.phoenix.pherf.run;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Provides an in-memory fake JDBC driver that handles both thick and thin Phoenix URLs without
 * requiring a live cluster. It records every connection request in {@link Records#CONNECTIONS} and
 * records the first close of each connection in {@link Records#CLOSES} using the identical entry
 * line. Connections synthesized by this driver maintain no underlying data: metadata lookups and
 * SQL queries return empty result sets, while each update statement reports modifying one row.
 */
public final class FakePhoenixDriver implements Driver {
  private static final String PREFIX = "jdbc:phoenix:";

  /**
   * Deregisters every currently registered JDBC driver that accepts a Phoenix URL and registers
   * this driver in their place.
   */
  public static void install() throws SQLException {
    for (Driver driver : Collections.list(DriverManager.getDrivers())) {
      if (
        driver.acceptsURL(PREFIX + "localhost")
          || driver.acceptsURL(PREFIX + "thin:url=http://localhost:8765")
      ) {
        DriverManager.deregisterDriver(driver);
      }
    }
    DriverManager.registerDriver(new FakePhoenixDriver());
  }

  @Override
  public Connection connect(String url, Properties info) {
    if (!acceptsURL(url)) {
      return null;
    }
    String line = Records.connectionLine(url, info);
    Records.append(Records.CONNECTIONS, line);
    return Mockito.mock(Connection.class,
      Mockito.withSettings().stubOnly().defaultAnswer(new FakeConnection(line)));
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 1;
  }

  @Override
  public int getMinorVersion() {
    return 0;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  private static <T> T mock(Class<T> type, String sql) {
    return Mockito.mock(type, Mockito.withSettings().stubOnly().defaultAnswer(new Fake(sql)));
  }

  private static boolean isQuery(String sql) {
    String head = sql == null ? "" : sql.trim().toUpperCase(Locale.ROOT);
    return head.startsWith("SELECT") || head.startsWith("EXPLAIN");
  }

  /**
   * Provides the Mockito default answer for a synthesized connection, tracking its closed state and
   * recording its initial close operation.
   */
  private static final class FakeConnection implements Answer<Object> {
    private final String line;
    private final Fake fake = new Fake(null);
    private volatile boolean closed;

    FakeConnection(String line) {
      this.line = line;
    }

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
      String name = invocation.getMethod().getName();
      if ("isClosed".equals(name)) {
        return closed;
      }
      if ("close".equals(name)) {
        synchronized (this) {
          if (!closed) {
            closed = true;
            Records.append(Records.CLOSES, line);
          }
        }
        return null;
      }
      return fake.answer(invocation);
    }
  }

  /**
   * Provides a unified Mockito default answer across all synthesized JDBC objects, while retaining
   * the associated SQL text for statement instances.
   */
  private static final class Fake implements Answer<Object> {
    private final String sql;

    Fake(String sql) {
      this.sql = sql;
    }

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
      String name = invocation.getMethod().getName();
      Class<?> type = invocation.getMethod().getReturnType();
      Object[] args = invocation.getArguments();
      if ("execute".equals(name)) {
        return isQuery(args.length > 0 ? (String) args[0] : sql);
      }
      if ("executeUpdate".equals(name)) {
        return 1;
      }
      if ("executeBatch".equals(name)) {
        return new int[0];
      }
      if ("getClientInfo".equals(name) && args.length == 0) {
        return new Properties();
      }
      if (type == PreparedStatement.class || type == Statement.class) {
        return mock(type, args.length > 0 ? (String) args[0] : null);
      }
      if (
        type == ResultSet.class || type == ResultSetMetaData.class || type == DatabaseMetaData.class
      ) {
        return mock(type, null);
      }
      return Mockito.RETURNS_DEFAULTS.answer(invocation);
    }
  }
}
