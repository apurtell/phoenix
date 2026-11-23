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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import org.apache.phoenix.pherf.spi.ConnectionProvider;

/**
 * Provides a cluster-independent Pherf connection provider for child JVM processes. Tests register
 * this provider in an overlay directory on the child classpath, and it appends one line to
 * {@link #LOG} in the working directory for every connection and statement. The resulting
 * connections store no data: queries return empty result sets, each update modifies a single row,
 * and metadata queries return the primary key (K1, K2) for every table.
 */
public final class RecordingConnectionProvider implements ConnectionProvider {
  public static final String LOG = "jdbc.log";
  private static final String[] KEYS = { "K1", "K2" };

  @Override
  public Connection getConnection(String url, Properties properties) {
    record("connect " + url);
    return proxy(Connection.class, null);
  }

  private static synchronized void record(String line) {
    try {
      Files.write(Paths.get(LOG), Collections.singletonList(line), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean isQuery(String sql) {
    String head = sql == null ? "" : sql.trim().toUpperCase(Locale.ROOT);
    return head.startsWith("SELECT") || head.startsWith("EXPLAIN");
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, String sql) {
    boolean[] closed = { false };
    return (T) Proxy.newProxyInstance(RecordingConnectionProvider.class.getClassLoader(),
      new Class<?>[] { type }, (proxy, method, args) -> {
        Object[] a = args == null ? new Object[0] : args;
        switch (method.getName()) {
          case "equals":
            return proxy == a[0];
          case "hashCode":
            return System.identityHashCode(proxy);
          case "toString":
            return "fake " + type.getSimpleName();
          case "close":
            closed[0] = true;
            return null;
          case "isClosed":
            return closed[0];
          case "prepareStatement":
          case "prepareCall":
            record("prepare " + a[0]);
            return proxy(PreparedStatement.class, (String) a[0]);
          case "createStatement":
            return proxy(Statement.class, null);
          case "execute":
            return isQuery(a.length > 0 ? (String) a[0] : sql);
          case "executeUpdate":
            return 1;
          case "executeBatch":
            return new int[0];
          case "getClientInfo":
            return a.length == 0 ? new Properties() : null;
          case "getPrimaryKeys":
            return primaryKeys();
          default:
            return answer(method);
        }
      });
  }

  /**
   * Produces the default response by returning a fake proxy for each JDBC object type, or zero,
   * false, or null for other return types.
   */
  private static Object answer(Method method) {
    Class<?> type = method.getReturnType();
    if (
      type == ResultSet.class || type == ResultSetMetaData.class || type == DatabaseMetaData.class
        || type == Statement.class || type == PreparedStatement.class || type == Connection.class
    ) {
      return proxy(type, null);
    }
    if (type == boolean.class || type == Boolean.class) {
      return false;
    }
    if (type == int.class || type == Integer.class) {
      return 0;
    }
    if (type == long.class || type == Long.class) {
      return 0L;
    }
    if (type == short.class || type == Short.class) {
      return (short) 0;
    }
    if (type == byte.class || type == Byte.class) {
      return (byte) 0;
    }
    if (type == double.class || type == Double.class) {
      return 0.0;
    }
    if (type == float.class || type == Float.class) {
      return 0.0f;
    }
    return null;
  }

  /**
   * Synthesizes a result set containing the primary key rows with COLUMN_NAME and KEY_SEQ values
   * for K1 and K2.
   */
  private static ResultSet primaryKeys() {
    int[] row = { 0 };
    return (ResultSet) Proxy.newProxyInstance(RecordingConnectionProvider.class.getClassLoader(),
      new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
        switch (method.getName()) {
          case "next":
            return ++row[0] <= KEYS.length;
          case "getString":
            return KEYS[row[0] - 1];
          case "getInt":
            return row[0];
          case "getShort":
            return (short) row[0];
          default:
            return answer(method);
        }
      });
  }
}
