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
package org.apache.phoenix.pherf.distributed.protocol;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the final report of a completed assignment, assembled from the output reports of the
 * run child process: the recording window (on the agent clock) and the count of each key indexed by
 * log tag from its JSON latency report, the schedule entries of that report preserved as they are,
 * and the rows of its health verdict. The controller compares these counts against the interval
 * frames that it holds.
 */
public final class FinalReport {
  private long startMs;
  private long endMs;
  private Map<String, Long> counts;
  private List<JsonObject> schedules;
  private List<String> verdict;

  private FinalReport() {
  }

  public FinalReport(long startMs, long endMs, Map<String, Long> counts, List<JsonObject> schedules,
    List<String> verdict) {
    this.startMs = startMs;
    this.endMs = endMs;
    this.counts = new LinkedHashMap<>(counts);
    this.schedules = new ArrayList<>(schedules);
    this.verdict = new ArrayList<>(verdict);
  }

  public long getStartMs() {
    return startMs;
  }

  public long getEndMs() {
    return endMs;
  }

  /** Returns the total count recorded for each key, indexed by its log tag. */
  public Map<String, Long> getCounts() {
    return counts == null ? Collections.<String, Long> emptyMap() : counts;
  }

  /** Returns the schedule entries from the JSON latency report, in run order. */
  public List<JsonObject> getSchedules() {
    return schedules == null ? Collections.<JsonObject> emptyList() : schedules;
  }

  /**
   * Returns the lines of the verdict file produced by the run child process, or an empty list when
   * no verdict file was written.
   */
  public List<String> getVerdict() {
    return verdict == null ? Collections.<String> emptyList() : verdict;
  }
}
