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
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.run.Records;

/**
 * Provides test extensions that a child run registers through META-INF/services, where each
 * extension writes the data it receives to a record file.
 */
public final class RecordingExtensions {
  private RecordingExtensions() {
  }

  /** Records each requested connection and opens it using the default connection provider. */
  public static class Provider implements ConnectionProvider {
    @Override
    public Connection getConnection(String url, Properties properties) throws SQLException {
      Records.append(Records.PROVIDER, Records.connectionLine(url, properties));
      return ConnectionProvider.DEFAULT.getConnection(url, properties);
    }
  }

  /** Records each metric entry with its name, sorted tags, and value. */
  public static class Sink implements MetricsSink {
    @Override
    public void record(String name, Map<String, String> tags, long value) {
      Records.append(Records.SINK, name + "\t" + new TreeMap<>(tags) + "\t" + value);
    }
  }

  /**
   * Provides a report section entry with the known key "known" and the name of the result as its
   * value.
   */
  public static class KnownKeySection implements ReportSection {
    @Override
    public String getName() {
      return "test";
    }

    @Override
    public Map<String, String> getEntries(DataModelResult result) {
      return Collections.singletonMap("known", result.getName());
    }
  }
}
