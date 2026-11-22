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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Properties;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.workload.cogen.CoGenFixtures;

/**
 * Wraps {@link StatementRecorder} to expose connection metadata that reports the primary key (K1,
 * K2) for every table, allowing co-generated range statements to resolve their leading key column
 * when running against the fake driver.
 */
public class KeyedStatementRecorder implements ConnectionProvider {
  @Override
  public Connection getConnection(String url, Properties properties) throws SQLException {
    final Connection connection = new StatementRecorder().getConnection(url, properties);
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
      new Class<?>[] { Connection.class }, (proxy, method, args) -> {
        if (method.getName().equals("getMetaData")) {
          return keyed(connection.getMetaData());
        }
        return invoke(method, connection, args);
      });
  }

  private static DatabaseMetaData keyed(final DatabaseMetaData metaData) {
    return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
      new Class<?>[] { DatabaseMetaData.class }, (proxy, method, args) -> {
        if (method.getName().equals("getPrimaryKeys")) {
          return CoGenFixtures.primaryKeys("K1", "K2");
        }
        return invoke(method, metaData, args);
      });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
