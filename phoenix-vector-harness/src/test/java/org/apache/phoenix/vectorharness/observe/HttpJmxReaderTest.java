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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.Test;

public class HttpJmxReaderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void testParseConfFindsTheKey() throws IOException {
    String json = "{\"properties\":[{\"key\":\"hbase.cluster.distributed\",\"value\":\"true\","
      + "\"isFinal\":false,\"resource\":\"hbase-site.xml\"},{\"key\":"
      + "\"phoenix.stats.collection.enabled\",\"value\":\"false\",\"isFinal\":false,"
      + "\"resource\":\"hbase-site.xml\"}]}";
    assertEquals("false",
      HttpJmxReader.parseConf(MAPPER.readTree(json), "phoenix.stats.collection.enabled"));
    assertEquals("true",
      HttpJmxReader.parseConf(MAPPER.readTree(json), "hbase.cluster.distributed"));
  }

  @Test
  public void testParseConfWithoutTheKey() throws IOException {
    assertNull(HttpJmxReader.parseConf(MAPPER.readTree("{\"properties\":[]}"), "a"));
    assertNull(HttpJmxReader.parseConf(MAPPER.readTree("{\"beans\":[]}"), "a"));
    assertNull(HttpJmxReader.parseConf(MAPPER.readTree("{\"properties\":[{\"key\":\"a\"}]}"), "a"));
    assertNull(HttpJmxReader.parseConf(null, "a"));
  }
}
