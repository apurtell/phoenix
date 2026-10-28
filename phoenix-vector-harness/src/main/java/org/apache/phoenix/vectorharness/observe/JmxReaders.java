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
package org.apache.phoenix.vectorharness.observe;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.ServerMetrics;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.HarnessConfig.JmxMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses the JMX reader of a run. */
public final class JmxReaders {

  private static final Logger LOG = LoggerFactory.getLogger(JmxReaders.class);

  private JmxReaders() {
  }

  /** A reader that reads nothing, for observe.jmx.mode=off. Every metric is unknown. */
  public static JmxReader none() {
    return new JmxReader() {
      @Override
      public String mode() {
        return "off";
      }

      @Override
      public Map<String, Map<String, Number>> read(String objectName) {
        return Collections.emptyMap();
      }

      @Override
      public void close() {
      }
    };
  }

  /**
   * Creates the reader for a profile. AUTO uses the RegionServer info servers when every live
   * server reports one and the first answers; else, when this JVM hosts RegionServers (a
   * minicluster), the in-process registry; else no metrics, with a warning, since a client JVM's
   * own registry would read as zeros.
   */
  public static JmxReader create(HarnessConfig config, Connection conn) {
    JmxMode mode = config.jmxMode();
    switch (mode) {
      case OFF:
        return none();
      case LOCAL:
        return new PlatformJmxReader();
      case REGISTRY:
        return new RegistryJmxReader();
      case HTTP:
        return new HttpJmxReader(infoUrls(conn, config.jmxPort(), true));
      default:
        List<String> urls = infoUrls(conn, config.jmxPort(), false);
        if (!urls.isEmpty()) {
          try {
            HttpJmxReader.fetch(urls.get(0), JmxReader.SERVER_BEAN);
            return new HttpJmxReader(urls);
          } catch (IOException e) {
            LOG.info("RegionServer info servers do not answer ({}); trying in-process metrics",
              e.getMessage());
          }
        }
        if (hostsRegionServer()) {
          return new RegistryJmxReader();
        }
        LOG.warn("No RegionServer metrics are reachable; vector index metrics are unknown."
          + " Set observe.jmx.mode and observe.jmx.port to read them.");
        return none();
    }
  }

  /** Returns true if this JVM hosts a RegionServer, whose server metrics bean it registers. */
  static boolean hostsRegionServer() {
    Map<String, Map<String, Number>> m = new PlatformJmxReader().read(JmxReader.SERVER_BEAN);
    return m.get(PlatformJmxReader.LOCAL) != null;
  }

  /** Returns the info server URLs of the live RegionServers. */
  static List<String> infoUrls(Connection conn, int portOverride, boolean required) {
    List<String> out = new ArrayList<>();
    try (Admin admin = HarnessContext.admin(conn)) {
      for (Map.Entry<ServerName, ServerMetrics> e : admin.getClusterMetrics().getLiveServerMetrics()
        .entrySet()) {
        int port = portOverride > 0 ? portOverride : e.getValue().getInfoServerPort();
        if (port <= 0) {
          if (required) {
            throw new IllegalStateException(
              "RegionServer " + e.getKey() + " reports no info port; set observe.jmx.port");
          }
          return Collections.emptyList();
        }
        out.add("http://" + e.getKey().getHostname() + ":" + port);
      }
    } catch (IOException | SQLException e) {
      if (required) {
        throw new IllegalStateException("Cannot list the RegionServers", e);
      }
      LOG.debug("Cannot list the RegionServers", e);
      return Collections.emptyList();
    }
    Collections.sort(out);
    return out;
  }
}
