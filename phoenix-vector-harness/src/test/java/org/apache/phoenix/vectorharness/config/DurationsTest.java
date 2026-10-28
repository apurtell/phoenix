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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.Locale;
import org.junit.Test;

public class DurationsTest {

  @Test
  public void testParseUnits() {
    assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
    assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
    assertEquals(Duration.ofSeconds(30), Durations.parse("30sec"));
    assertEquals(Duration.ofSeconds(90), Durations.parse("1.5m"));
    assertEquals(Duration.ofMinutes(2), Durations.parse("2min"));
    assertEquals(Duration.ofHours(2), Durations.parse("2h"));
    assertEquals(Duration.ofDays(1), Durations.parse("1d"));
    assertEquals(Duration.ofMillis(250), Durations.parse("0.25s"));
    assertEquals(Duration.ZERO, Durations.parse("0"));
    assertEquals(Duration.ZERO, Durations.parse("0ms"));
  }

  @Test
  public void testBareNumberIsSeconds() {
    assertEquals(Duration.ofSeconds(45), Durations.parse("45"));
    assertEquals(Duration.ofMillis(500), Durations.parse("0.5"));
    assertEquals(Duration.ofSeconds(1000), Durations.parse("1e3"));
  }

  @Test
  public void testParseIgnoresCaseAndWhitespace() {
    assertEquals(Duration.ofMinutes(10), Durations.parse(" 10 M "));
    assertEquals(Duration.ofMillis(5), Durations.parse("5MS"));
    assertEquals(Duration.ofHours(3), Durations.parse("\t3H\n"));
  }

  @Test
  public void testParseRoundsToMillis() {
    assertEquals(Duration.ofMillis(2), Durations.parse("1.5ms"));
    assertEquals(Duration.ofMillis(1), Durations.parse("0.0005s"));
  }

  @Test
  public void testParseCompound() {
    assertEquals(Duration.ofSeconds(90), Durations.parse("1m30s"));
    assertEquals(Duration.ofHours(1).plusMinutes(5).plusSeconds(7), Durations.parse("1h5m7s"));
    assertEquals(Duration.ofSeconds(61).plusMillis(500), Durations.parse("1m1.5s"));
    assertEquals(Duration.ofSeconds(2).plusMillis(250), Durations.parse("2s 250ms"));
  }

  @Test
  public void testParseInvalid() {
    for (String bad : new String[] { "", "   ", "abc", "10x", "-1s", "-5", "ms", "NaN", "Infinity",
      "1.2.3s", "s10", "1m30", "1m-30s", "1mm" }) {
      try {
        Durations.parse(bad);
        fail("Expected an exception for '" + bad + "'");
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().toLowerCase(Locale.ROOT).contains("duration"));
      }
    }
  }

  @Test
  public void testFormat() {
    assertEquals("0ms", Durations.format(Duration.ZERO));
    assertEquals("250ms", Durations.format(Duration.ofMillis(250)));
    assertEquals("999ms", Durations.format(Duration.ofMillis(999)));
    assertEquals("1s", Durations.format(Duration.ofSeconds(1)));
    assertEquals("30s", Durations.format(Duration.ofSeconds(30)));
    assertEquals("1m30s", Durations.format(Duration.ofSeconds(90)));
    assertEquals("10m", Durations.format(Duration.ofMinutes(10)));
    assertEquals("2h", Durations.format(Duration.ofHours(2)));
    assertEquals("1h5s", Durations.format(Duration.ofHours(1).plusSeconds(5)));
    assertEquals("25h", Durations.format(Duration.ofHours(25)));
    assertEquals("1.5s", Durations.format(Duration.ofMillis(1500)));
    assertEquals("2m0.25s", Durations.format(Duration.ofMillis(120_250)));
  }

  @Test
  public void testFormatNeverShowsSixtySeconds() {
    // 59.999 seconds must not be rounded up to 60.0s
    String s = Durations.format(Duration.ofMillis(59_999));
    assertEquals("59.999s", s);
    assertEquals("1m59.999s", Durations.format(Duration.ofMillis(119_999)));
  }

  @Test
  public void testParseFormatRoundTrip() {
    for (String text : new String[] { "500ms", "30s", "1.5m", "2h", "45", "1d", "0", "1ms", "999ms",
      "1.5s", "59.999s", "61.005s", "3h7m9.5s", "100h", "0.001s", "12m" }) {
      Duration d = Durations.parse(text);
      String formatted = Durations.format(d);
      assertEquals(text + " -> " + formatted, d, Durations.parse(formatted));
    }
    for (long ms = 0; ms < 200_000; ms += 997) {
      Duration d = Duration.ofMillis(ms);
      assertEquals(Durations.format(d), d, Durations.parse(Durations.format(d)));
    }
  }
}
