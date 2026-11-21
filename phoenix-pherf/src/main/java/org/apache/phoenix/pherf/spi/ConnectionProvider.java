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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Service provider interface responsible for opening all Pherf JDBC connections. Pherf constructs
 * the standard thick or thin Phoenix URL along with the resolved connection properties, and the
 * provider opens and optionally wraps the resulting connection.
 * <p>
 * Implementations are registered on the class path via
 * META-INF/services/org.apache.phoenix.pherf.spi.ConnectionProvider. When no implementation is
 * registered, Pherf falls back to {@link #DEFAULT}. Registering more than one implementation is
 * treated as an error.
 */
public interface ConnectionProvider {
  /** The default provider, which opens connections directly through DriverManager. */
  ConnectionProvider DEFAULT = (url, properties) -> DriverManager.getConnection(url, properties);

  Connection getConnection(String url, Properties properties) throws SQLException;
}
