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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads beans from the {@code /jmx} servlet of the info server of each RegionServer, which serves
 * the bean attributes as JSON. This is the reader for real clusters.
 */
public final class HttpJmxReader implements JmxReader {

  private static final Logger LOG = LoggerFactory.getLogger(HttpJmxReader.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  static final int TIMEOUT_MS = 10_000;

  private final List<String> baseUrls;

  /**
   * @param baseUrls the info server base URLs, such as http://rs1.example.com:16030
   */
  public HttpJmxReader(List<String> baseUrls) {
    this.baseUrls = baseUrls;
  }

  @Override
  public String mode() {
    return "http";
  }

  @Override
  public Map<String, Map<String, Number>> read(String objectName) {
    Map<String, Map<String, Number>> out = new LinkedHashMap<>();
    for (String base : baseUrls) {
      try {
        out.put(base, fetch(base, objectName));
      } catch (IOException e) {
        LOG.debug("Reading {} from {} failed", objectName, base, e);
        out.put(base, null);
      }
    }
    return out;
  }

  static Map<String, Number> fetch(String base, String objectName) throws IOException {
    URL url = new URL(base + "/jmx?qry=" + URLEncoder.encode(objectName, "UTF-8"));
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(TIMEOUT_MS);
    c.setReadTimeout(TIMEOUT_MS);
    try (InputStream in = c.getInputStream()) {
      return parse(MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    } finally {
      c.disconnect();
    }
  }

  /** Parses the response of the servlet; a missing bean gives null. */
  static Map<String, Number> parse(JsonNode root) {
    JsonNode beans = root.get("beans");
    if (beans == null || !beans.isArray() || beans.size() == 0) {
      return null;
    }
    Map<String, Number> attrs = new LinkedHashMap<>();
    JsonNode bean = beans.get(0);
    for (Iterator<Map.Entry<String, JsonNode>> it = bean.fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode v = e.getValue();
      if (v.isNumber()) {
        attrs.put(e.getKey(), v.numberValue());
      }
    }
    return attrs;
  }

  @Override
  public void close() {
  }
}
