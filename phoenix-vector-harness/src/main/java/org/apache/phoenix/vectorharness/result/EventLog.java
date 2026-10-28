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
package org.apache.phoenix.vectorharness.result;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The timeline of a run: stage boundaries, lifecycle transitions, disruptions, plan changes, and
 * warnings, each stamped with wall time and the offset from the start of the run, so that they
 * align with the metric, latency, and recall time series. Events are kept in memory for the summary
 * and appended to events.csv.
 */
public final class EventLog implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(EventLog.class);

  /** One event. */
  public static final class Event {
    private final long wallMillis;
    private final long offsetMillis;
    private final String stage;
    private final String kind;
    private final String detail;

    Event(long wallMillis, long offsetMillis, String stage, String kind, String detail) {
      this.wallMillis = wallMillis;
      this.offsetMillis = offsetMillis;
      this.stage = stage;
      this.kind = kind;
      this.detail = detail;
    }

    public long wallMillis() {
      return wallMillis;
    }

    public long offsetMillis() {
      return offsetMillis;
    }

    public String stage() {
      return stage;
    }

    public String kind() {
      return kind;
    }

    public String detail() {
      return detail;
    }
  }

  private final long startMillis;
  private final CsvTable csv;
  private final List<Event> events = new ArrayList<>();
  private volatile String stage = "init";

  public EventLog(Path file, long startMillis) throws IOException {
    this.startMillis = startMillis;
    this.csv = file == null
      ? null
      : CsvTable.create(file, "wall_ms", "offset_ms", "stage", "kind", "detail");
  }

  /** Returns an event log that keeps events in memory only. */
  public static EventLog inMemory(long startMillis) {
    try {
      return new EventLog(null, startMillis);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public long startMillis() {
    return startMillis;
  }

  /** Returns the milliseconds since the start of the run. */
  public long offset() {
    return System.currentTimeMillis() - startMillis;
  }

  public String stage() {
    return stage;
  }

  /** Starts a stage and records its boundary. */
  public void stage(String name) {
    event("stage", name);
    this.stage = name;
  }

  public void event(String kind, String detail) {
    long now = System.currentTimeMillis();
    Event e = new Event(now, now - startMillis, stage, kind, detail);
    synchronized (events) {
      events.add(e);
    }
    if (csv != null) {
      csv.row(e.wallMillis, e.offsetMillis, e.stage, e.kind, e.detail);
      csv.flush();
    }
    LOG.info("[{}] {}: {}", stage, kind, detail);
  }

  public List<Event> events() {
    synchronized (events) {
      return Collections.unmodifiableList(new ArrayList<>(events));
    }
  }

  /** Returns the events of one kind. */
  public List<Event> events(String kind) {
    List<Event> out = new ArrayList<>();
    for (Event e : events()) {
      if (e.kind.equals(kind)) {
        out.add(e);
      }
    }
    return out;
  }

  @Override
  public void close() {
    if (csv != null) {
      csv.close();
    }
  }
}
