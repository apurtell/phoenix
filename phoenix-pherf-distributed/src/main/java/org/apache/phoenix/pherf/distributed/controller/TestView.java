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
package org.apache.phoenix.pherf.distributed.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an immutable snapshot of a single test, associating one execution plan with the runs
 * of that plan in submission order. This view is returned by the REST API and is persisted to
 * tests/&lt;id&gt;/test.json in the identical Gson format.
 */
public final class TestView {
  /** Defines the lifecycle states of a test. */
  public enum State {
    /** Indicates that the test is active and accepts new runs. */
    OPEN,
    /** Indicates that the test is closed to new runs, while its existing runs remain readable. */
    ENDED
  }

  private final String id;
  private final State state;
  private final long createdMs;
  /**
   * Remains null until the test ends so that the serialized JSON representation omits the endedMs
   * field while the test is open.
   */
  private final Long endedMs;
  private final List<String> runs;

  TestView(String id, State state, long createdMs, Long endedMs, List<String> runs) {
    this.id = id;
    this.state = state;
    this.createdMs = createdMs;
    this.endedMs = endedMs;
    this.runs = Collections.unmodifiableList(new ArrayList<>(runs));
  }

  public String getId() {
    return id;
  }

  public State getState() {
    return state;
  }

  /** Returns the controller clock timestamp when the test was created. */
  public long getCreatedMs() {
    return createdMs;
  }

  /** Returns the controller clock timestamp when the test ended, or null while it remains OPEN. */
  public Long getEndedMs() {
    return endedMs;
  }

  /** Returns the run identifiers belonging to the test, ordered by submission sequence. */
  public List<String> getRuns() {
    return runs;
  }

  @Override
  public String toString() {
    return id + " " + state + " " + runs;
  }
}
