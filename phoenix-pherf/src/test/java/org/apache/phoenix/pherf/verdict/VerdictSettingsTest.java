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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.Test;

/**
 * Verifies the health verdict threshold configuration, covering default values, explicit overrides
 * for each property key, and validation errors that identify the offending key.
 */
public class VerdictSettingsTest {

  private static VerdictSettings of(String... keyAndValue) {
    Properties properties = new Properties();
    // Configuration keys belonging to other parts of Pherf are not read by the verdict parser.
    properties.setProperty("pherf.default.threadpool", "10");
    for (int i = 0; i < keyAndValue.length; i += 2) {
      properties.setProperty(keyAndValue[i], keyAndValue[i + 1]);
    }
    return VerdictSettings.of(properties);
  }

  private static void assertDefaults(VerdictSettings settings) {
    assertEquals(90.0, settings.getAttainmentMinPct(), 0);
    assertEquals(1_000_000L, settings.getLagMaxUs());
    assertEquals(1_000_000L, settings.getP99MaxUs());
    assertEquals(5_000_000L, settings.getP999MaxUs());
    assertEquals(1.5, settings.getReadSkewMax(), 0);
    assertFalse(settings.isNoisyHost());
    assertEquals(Collections.<String> emptyList(), settings.getServerJmx());
  }

  @Test
  public void testDefaults() {
    assertDefaults(of());
  }

  @Test
  public void testEmptyMeansDefault() {
    for (String empty : new String[] { "", "  " }) {
      assertDefaults(of(VerdictSettings.MIN_PCT, empty, VerdictSettings.LAG_MAX_MS, empty,
        VerdictSettings.P99_MAX_MS, empty, VerdictSettings.P999_MAX_MS, empty,
        VerdictSettings.READ_SKEW_MAX, empty, VerdictSettings.NOISY_HOST, empty,
        VerdictSettings.SERVER_JMX, empty));
    }
  }

  @Test
  public void testEachKey() {
    VerdictSettings settings = of(VerdictSettings.MIN_PCT, "75.5", VerdictSettings.LAG_MAX_MS,
      " 250 ", VerdictSettings.P99_MAX_MS, "0.5", VerdictSettings.P999_MAX_MS, "2500.0004",
      VerdictSettings.READ_SKEW_MAX, "2", VerdictSettings.NOISY_HOST, "true",
      VerdictSettings.SERVER_JMX, "http://rs1:16030, https://rs2:16031/");
    assertEquals(75.5, settings.getAttainmentMinPct(), 0);
    assertEquals(250_000L, settings.getLagMaxUs());
    assertEquals(500L, settings.getP99MaxUs());
    // Converting the bound to us truncates any fractional component below 1 us, ensuring that an
    // integer value in us exceeds the threshold only when it exceeds the configured bound in ms.
    assertEquals(2_500_000L, settings.getP999MaxUs());
    assertEquals(2.0, settings.getReadSkewMax(), 0);
    assertTrue(settings.isNoisyHost());
    assertEquals(Arrays.asList("http://rs1:16030", "https://rs2:16031/"), settings.getServerJmx());

    // Verify the boundary limits of each valid parameter range.
    assertEquals(100.0, of(VerdictSettings.MIN_PCT, "100").getAttainmentMinPct(), 0);
    assertEquals(0L, of(VerdictSettings.LAG_MAX_MS, "0").getLagMaxUs());
    assertEquals(1.0, of(VerdictSettings.READ_SKEW_MAX, "1").getReadSkewMax(), 0);
    assertFalse(of(VerdictSettings.NOISY_HOST, "false").isNoisyHost());
  }

  @Test
  public void testEachBadValueNamesItsKey() {
    String[][] bad = { { VerdictSettings.MIN_PCT, "0" }, { VerdictSettings.MIN_PCT, "100.1" },
      { VerdictSettings.MIN_PCT, "abc" }, { VerdictSettings.MIN_PCT, "NaN" },
      { VerdictSettings.LAG_MAX_MS, "-1" }, { VerdictSettings.LAG_MAX_MS, "1e30" },
      { VerdictSettings.P99_MAX_MS, "0" }, { VerdictSettings.P99_MAX_MS, "-2" },
      { VerdictSettings.P999_MAX_MS, "x" }, { VerdictSettings.READ_SKEW_MAX, "0.9" },
      { VerdictSettings.READ_SKEW_MAX, "Infinity" }, { VerdictSettings.NOISY_HOST, "yes" },
      { VerdictSettings.SERVER_JMX, "ftp://rs1:16030" },
      { VerdictSettings.SERVER_JMX, "not a url" },
      { VerdictSettings.SERVER_JMX, "http://rs1:16030,http://rs1:16030" },
      { VerdictSettings.SERVER_JMX, "http://rs1:16030,,http://rs2:16030" },
      { VerdictSettings.SERVER_JMX, "http:///jmx" } };
    for (String[] pair : bad) {
      try {
        of(pair[0], pair[1]);
        fail("Accepted " + pair[0] + "=" + pair[1]);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(pair[0]));
      }
    }
  }
}
