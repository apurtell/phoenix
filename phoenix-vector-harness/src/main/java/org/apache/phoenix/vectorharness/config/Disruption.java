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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One timed disruption of the steady state, at an offset from the start of the measured duration.
 * The schedule syntax is a comma separated list of {@code <offset>:<kind>}, such as
 * {@code 2m:split,5m:compact}.
 */
public final class Disruption {

  /** The disruptions that the chaos injector can apply through administrative APIs. */
  public enum Kind {
    /** Move a region of the data table to another RegionServer. */
    MOVE,
    /** Restart a RegionServer that hosts the data table. */
    RESTART,
    /** Split a region of the data table. */
    SPLIT,
    /** Merge two adjacent regions of the data table. */
    MERGE,
    /** Major compact the data table. */
    COMPACT
  }

  private final Duration at;
  private final Kind kind;

  public Disruption(Duration at, Kind kind) {
    this.at = at;
    this.kind = kind;
  }

  public Duration at() {
    return at;
  }

  public Kind kind() {
    return kind;
  }

  @Override
  public String toString() {
    return Durations.format(at) + ":" + kind.name().toLowerCase(Locale.ROOT);
  }

  /** Parses a schedule, sorted by offset. A null or empty schedule has no disruptions. */
  public static List<Disruption> parseSchedule(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<Disruption> list = new ArrayList<>();
    for (String part : text.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) {
        continue;
      }
      int colon = p.indexOf(':');
      if (colon <= 0 || colon == p.length() - 1) {
        throw new IllegalArgumentException("Invalid disruption, expected <offset>:<kind>: " + p);
      }
      Duration at = Durations.parse(p.substring(0, colon));
      Kind kind;
      try {
        kind = Kind.valueOf(p.substring(colon + 1).trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Unknown disruption kind in " + p
          + " (expected move, restart, split, merge, or compact)");
      }
      list.add(new Disruption(at, kind));
    }
    list.sort(Comparator.comparing(Disruption::at));
    return Collections.unmodifiableList(list);
  }
}
