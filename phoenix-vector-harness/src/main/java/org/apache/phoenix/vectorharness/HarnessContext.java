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
package org.apache.phoenix.vectorharness;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.CorpusFactory;
import org.apache.phoenix.vectorharness.result.RunStore;
import org.apache.phoenix.vectorharness.workload.TableNames;

/**
 * What every phase needs: the profile, the corpus, the effective metric, the table names, the
 * output store, and connections to the cluster under test. Connections carry the Phoenix client
 * properties of the profile; HBase administration and configuration come from the Phoenix
 * connection's query services, so the harness needs no second client configuration.
 */
public final class HarnessContext implements Closeable {

  private static volatile Configuration clusterConfiguration;

  private final HarnessConfig config;
  private final Corpus corpus;
  private final Metric metric;
  private final TableNames tables;
  private final RunStore store;

  private HarnessContext(HarnessConfig config, Corpus corpus) {
    this.config = config;
    this.corpus = corpus;
    this.metric = CorpusFactory.effectiveMetric(config, corpus);
    this.tables = TableNames.of(config);
    this.store = RunStore.of(config);
  }

  /** Opens the corpus of a profile and validates the profile. */
  public static HarnessContext open(HarnessConfig config) throws IOException {
    config.validateOrThrow();
    Corpus corpus = CorpusFactory.open(config);
    return new HarnessContext(config, corpus);
  }

  public HarnessConfig config() {
    return config;
  }

  public Corpus corpus() {
    return corpus;
  }

  public Metric metric() {
    return metric;
  }

  public TableNames tables() {
    return tables;
  }

  public RunStore store() {
    return store;
  }

  /** Opens a global connection. */
  public Connection connection() throws SQLException {
    return DriverManager.getConnection(config.jdbcUrl(), config.clientProperties());
  }

  /** Opens a connection with extra connection properties. */
  public Connection connection(Properties extra) throws SQLException {
    Properties p = config.clientProperties();
    p.putAll(extra);
    return DriverManager.getConnection(config.jdbcUrl(), p);
  }

  /** Opens a tenant-specific connection, which scopes queries of multi-tenant tables. */
  public Connection tenantConnection(String tenantId) throws SQLException {
    Properties p = config.clientProperties();
    p.setProperty(PhoenixRuntime.TENANT_ID_ATTRIB, tenantId);
    return DriverManager.getConnection(config.jdbcUrl(), p);
  }

  /** Returns the query services behind a connection. */
  public static ConnectionQueryServices services(Connection conn) throws SQLException {
    return conn.unwrap(PhoenixConnection.class).getQueryServices();
  }

  /** Returns a new HBase Admin of the cluster of a connection; the caller closes it. */
  public static Admin admin(Connection conn) throws SQLException {
    return services(conn).getAdmin();
  }

  /**
   * Sets the HBase configuration of the cluster under test, for a process that hosts the cluster
   * itself, such as a minicluster test, where the configuration of the Phoenix connection does not
   * name the cluster's root directory and filesystem. Null restores the default.
   */
  public static void setClusterConfiguration(Configuration conf) {
    clusterConfiguration = conf;
  }

  /**
   * Returns the HBase configuration of the cluster of a connection, which the MapReduce tools and
   * the filesystem measurements use: the configuration set with
   * {@link #setClusterConfiguration(Configuration)}, else that of the connection's query services.
   */
  public static Configuration configuration(Connection conn) throws SQLException {
    Configuration c = clusterConfiguration;
    return c != null ? c : services(conn).getConfiguration();
  }

  @Override
  public void close() {
    corpus.close();
  }
}
