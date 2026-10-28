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
package org.apache.phoenix.vectorharness.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class SettingTest {

  @Test
  public void testForKeyRoundTrip() {
    for (Setting s : Setting.values()) {
      assertSame(s, Setting.forKey(s.key()));
    }
  }

  @Test
  public void testForKeyUnknown() {
    assertNull(Setting.forKey("no.such.key"));
    assertNull(Setting.forKey("CORPUS.DIM"));
    assertNull(Setting.forKey(""));
  }

  @Test
  public void testKeysAreUniqueAndWellFormed() {
    Set<String> keys = new HashSet<>();
    for (Setting s : Setting.values()) {
      assertTrue("Duplicate key " + s.key(), keys.add(s.key()));
      assertTrue(s.key(), s.key().matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+"));
      assertFalse(s.key(), s.description().trim().isEmpty());
      if (s.defaultText() != null) {
        assertEquals(s.key(), s.defaultText().trim(), s.defaultText());
        assertFalse(s.key(), s.defaultText().isEmpty());
      }
    }
  }

  @Test
  public void testDocumentedDefaults() {
    assertEquals("ivf", Setting.RUN_VARIANT.defaultText());
    assertEquals("synthetic", Setting.CORPUS_SOURCE.defaultText());
    assertEquals("42", Setting.CORPUS_SEED.defaultText());
    assertEquals("1000000", Setting.CORPUS_ROWS.defaultText());
    assertEquals("768", Setting.CORPUS_DIM.defaultText());
    assertEquals("200", Setting.CORPUS_CLUSTERS.defaultText());
    assertEquals("dim", Setting.CORPUS_INTRINSIC_DIM.defaultText());
    assertEquals("true for COSINE", Setting.CORPUS_NORMALIZE.defaultText());
    assertEquals("10,100", Setting.QUERY_K_VALUES.defaultText());
    assertEquals("1,4,16,64,all", Setting.QUERY_IVF_PROBE_VALUES.defaultText());
    assertEquals("16,32,64,128,256,512", Setting.QUERY_HNSW_EF_VALUES.defaultText());
    assertEquals("0.001,0.01,0.1,1.0", Setting.QUERY_FILTER_SELECTIVITY.defaultText());
    assertEquals("1m", Setting.STEADY_STATE_WARMUP.defaultText());
    assertEquals("10m", Setting.STEADY_STATE_DURATION.defaultText());
    assertEquals("30s", Setting.RECALL_SAMPLE_INTERVAL.defaultText());
    assertEquals("15s", Setting.OBSERVE_INTERVAL.defaultText());
    assertEquals("2h", Setting.INDEX_BUILD_TIMEOUT.defaultText());
    assertEquals("60s", Setting.QUERY_TIMEOUT.defaultText());
    assertEquals("0.3,0.5,0.2", Setting.MUTATION_MIX.defaultText());
    assertEquals("corpus,profile", Setting.TABLE_LAYOUTS.defaultText());
  }

  @Test
  public void testSettingsWithoutDefaults() {
    Setting[] none =
      { Setting.RUN_ID, Setting.RUN_PROFILE_NAME, Setting.CORPUS_PATH, Setting.CORPUS_NAME,
        Setting.TABLE_SUFFIX, Setting.HNSW_PQ_SEGMENTS, Setting.QUERY_RATE_VALUES,
        Setting.REBUILD_EXPLICIT_AT, Setting.OBSERVE_JMX_PORT, Setting.DISRUPTION_SCHEDULE };
    Set<Setting> expected = new HashSet<>();
    for (Setting s : none) {
      expected.add(s);
    }
    Set<Setting> actual = new HashSet<>();
    for (Setting s : Setting.values()) {
      if (s.defaultText() == null) {
        actual.add(s);
      }
    }
    assertEquals(expected, actual);
  }
}
