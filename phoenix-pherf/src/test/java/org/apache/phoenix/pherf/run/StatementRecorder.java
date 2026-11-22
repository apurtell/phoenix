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
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.apache.phoenix.pherf.spi.ConnectionProvider;

/**
 * A connection provider registered during child JVM test runs that wraps each JDBC connection to
 * record the SQL text of every prepared statement in {@link #RECORD} in invocation order.
 */
public class StatementRecorder implements ConnectionProvider {
  public static final String RECORD = "statements.log";

  @Override
  public Connection getConnection(String url, Properties properties) throws SQLException {
    final Connection connection = ConnectionProvider.DEFAULT.getConnection(url, properties);
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
      new Class<?>[] { Connection.class }, (proxy, method, args) -> {
        if (method.getName().equals("prepareStatement") && args != null && args.length > 0) {
          Records.append(RECORD, (String) args[0]);
        }
        try {
          return method.invoke(connection, args);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      });
  }
}
