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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.phoenix.vectorharness.config.Disruption.Kind;
import org.junit.Test;

public class DisruptionTest {

  @Test
  public void testEmptySchedules() {
    assertTrue(Disruption.parseSchedule(null).isEmpty());
    assertTrue(Disruption.parseSchedule("").isEmpty());
    assertTrue(Disruption.parseSchedule("   ").isEmpty());
    assertTrue(Disruption.parseSchedule(" , ").isEmpty());
  }

  @Test
  public void testParseSortsByOffset() {
    List<Disruption> d = Disruption.parseSchedule("5m:compact, 30s:MOVE,2m:split,1h:restart");
    assertEquals(4, d.size());
    assertEquals(Duration.ofSeconds(30), d.get(0).at());
    assertSame(Kind.MOVE, d.get(0).kind());
    assertEquals(Duration.ofMinutes(2), d.get(1).at());
    assertSame(Kind.SPLIT, d.get(1).kind());
    assertEquals(Duration.ofMinutes(5), d.get(2).at());
    assertSame(Kind.COMPACT, d.get(2).kind());
    assertEquals(Duration.ofHours(1), d.get(3).at());
    assertSame(Kind.RESTART, d.get(3).kind());
  }

  @Test
  public void testEqualOffsetsKeepScheduleOrder() {
    List<Disruption> d = Disruption.parseSchedule("2m:split,1m:move,2m:compact,2m:merge");
    List<Kind> kinds = new ArrayList<>();
    for (Disruption x : d) {
      kinds.add(x.kind());
    }
    List<Kind> expected = new ArrayList<>();
    expected.add(Kind.MOVE);
    expected.add(Kind.SPLIT);
    expected.add(Kind.COMPACT);
    expected.add(Kind.MERGE);
    assertEquals(expected, kinds);
  }

  @Test
  public void testEveryKind() {
    for (Kind k : Kind.values()) {
      List<Disruption> d = Disruption.parseSchedule("10s:" + k.name().toLowerCase(Locale.ROOT));
      assertEquals(1, d.size());
      assertSame(k, d.get(0).kind());
    }
  }

  @Test
  public void testScheduleIsUnmodifiable() {
    List<Disruption> d = Disruption.parseSchedule("2m:split");
    try {
      d.add(new Disruption(Duration.ZERO, Kind.MOVE));
      fail("Expected an exception");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void testToStringRoundTrip() {
    Disruption d = new Disruption(Duration.ofMinutes(2), Kind.SPLIT);
    assertEquals("2m:split", d.toString());
    Disruption odd = new Disruption(Duration.ofSeconds(90), Kind.MERGE);
    assertEquals("1m30s:merge", odd.toString());
    List<Disruption> parsed = Disruption.parseSchedule(odd + "," + d);
    assertEquals(2, parsed.size());
    assertEquals(Duration.ofSeconds(90), parsed.get(0).at());
    assertSame(Kind.MERGE, parsed.get(0).kind());
    assertEquals(d.toString(), parsed.get(1).toString());
  }

  @Test
  public void testInvalidSchedules() {
    String[][] bad =
      { { "split", "expected <offset>:<kind>" }, { ":split", "expected" }, { "2m:", "expected" },
        { "2m:explode", "Unknown disruption kind" }, { "2m:split,5m:explode", "explode" },
        { "soon:split", "duration" }, { "-1m:split", "duration" } };
    for (String[] b : bad) {
      try {
        Disruption.parseSchedule(b[0]);
        fail("Expected an exception for " + b[0]);
      } catch (IllegalArgumentException e) {
        assertTrue(b[0] + ": " + e.getMessage(), e.getMessage().contains(b[1]));
      }
    }
  }
}
