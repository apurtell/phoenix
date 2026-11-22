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
package org.apache.phoenix.pherf.verdict;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Reads;
import org.apache.phoenix.util.EnvironmentEdgeManager;

/**
 * Collects the read counts for each RegionServer from the table metrics bean exposed by its JMX
 * servlet. Each server is represented by a configured info server base URL, which also serves as
 * its name in the verdict. The read count for a server is computed by summing readRequestCount
 * across all of its user tables, while excluding the hbase namespace, the SYSTEM namespace, and any
 * SYSTEM.* tables within the default namespace. If a server does not expose a table metrics bean,
 * its read count defaults to 0.
 */
final class ServerReads {
  static final String BEAN = "Hadoop:service=HBase,name=RegionServer,sub=Tables";
  static final String QUERY = "?qry=" + BEAN;
  /** Defines the connection and read timeout applied to each individual request. */
  static final int TIMEOUT_MS = 10_000;
  private static final String NAMESPACE = "Namespace_";
  private static final String TABLE = "_table_";
  private static final String READS = "_metric_readRequestCount";

  private final List<String> urls;
  private Map<String, Long> begin;
  private long beginMs;
  private String error;

  ServerReads(List<String> urls) {
    this.urls = urls;
  }

  /** Captures the first sample and records any failure to be reported by {@link #finish()}. */
  void begin() {
    try {
      begin = sample();
      beginMs = EnvironmentEdgeManager.currentTimeMillis();
    } catch (IOException e) {
      error = "at begin: " + e.getMessage();
    }
  }

  /**
   * Captures the second sample and returns the read counts accumulated by each server between the
   * two samples. The measurement window spans the elapsed time from the completion of the first
   * sample to the completion of the second sample.
   */
  Reads finish() {
    if (urls.isEmpty()) {
      return Reads.skip("no source: " + VerdictSettings.SERVER_JMX + " is empty");
    }
    if (error != null) {
      return Reads.error(error);
    }
    if (begin == null) {
      return Reads.skip("the run stopped before the measured work started");
    }
    try {
      Map<String, Long> end = sample();
      return Reads.between(begin, end, EnvironmentEdgeManager.currentTimeMillis() - beginMs);
    } catch (IOException e) {
      return Reads.error("at finish: " + e.getMessage());
    }
  }

  private Map<String, Long> sample() throws IOException {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String url : urls) {
      counts.put(url, fetch(url));
    }
    return counts;
  }

  /**
   * Retrieves the user table read count from a single server, including the target URL in the error
   * message if the request fails.
   */
  static long fetch(String baseUrl) throws IOException {
    String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String url = base + "/jmx" + QUERY;
    try {
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      try {
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
          throw new IOException("HTTP " + code);
        }
        try (InputStream in = connection.getInputStream()) {
          ByteArrayOutputStream body = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          for (int n; (n = in.read(buffer)) > 0;) {
            body.write(buffer, 0, n);
          }
          return userTableReads(new String(body.toByteArray(), StandardCharsets.UTF_8));
        }
      } finally {
        connection.disconnect();
      }
    } catch (IOException | RuntimeException e) {
      throw new IOException("cannot read " + url + ": " + e, e);
    }
  }

  /**
   * Computes the sum of readRequestCount across all user tables in the table metrics bean of a JMX
   * document.
   */
  static long userTableReads(String json) {
    long reads = 0;
    for (JsonElement element : JsonParser.parseString(json).getAsJsonObject()
      .getAsJsonArray("beans")) {
      JsonObject bean = element.getAsJsonObject();
      if (!bean.has("name") || !BEAN.equals(bean.get("name").getAsString())) {
        continue;
      }
      for (Map.Entry<String, JsonElement> attribute : bean.entrySet()) {
        String name = attribute.getKey();
        int table = name.indexOf(TABLE, NAMESPACE.length());
        if (
          !name.startsWith(NAMESPACE) || !name.endsWith(READS) || table < 0
            || table + TABLE.length() > name.length() - READS.length()
        ) {
          continue;
        }
        String namespace = name.substring(NAMESPACE.length(), table);
        String qualifier = name.substring(table + TABLE.length(), name.length() - READS.length());
        if (
          !"hbase".equals(namespace) && !"SYSTEM".equals(namespace)
            && !("default".equals(namespace) && qualifier.startsWith("SYSTEM."))
        ) {
          reads += attribute.getValue().getAsLong();
        }
      }
    }
    return reads;
  }
}
