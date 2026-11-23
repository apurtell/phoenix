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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines the specification for a single step of a run, encompassing the required number of agents
 * and their weights, the scenario name and supporting documents, the Pherf options, the aggregate
 * step totals, and the step properties. Instances are constructed exclusively by
 * {@link RunSpec#parse}.
 */
public final class StepSpec {
  private final long[] weights;
  private final String scenario;
  private final Map<String, String> documents;
  private final List<String> args;
  private final Long rate;
  private final Long durationMs;
  private final Long threads;
  private final Long rows;
  private final Map<String, String> properties;

  StepSpec(long[] weights, String scenario, Map<String, String> documents, List<String> args,
    Long rate, Long durationMs, Long threads, Long rows, Map<String, String> properties) {
    this.weights = weights.clone();
    this.scenario = scenario;
    this.documents = new LinkedHashMap<>(documents);
    this.args = new ArrayList<>(args);
    this.rate = rate;
    this.durationMs = durationMs;
    this.threads = threads;
    this.rows = rows;
    this.properties = new LinkedHashMap<>(properties);
  }

  /**
   * Returns the number of agents required for this step, with one agent corresponding to each
   * configured weight.
   */
  public int getAgents() {
    return weights.length;
  }

  /** Returns the relative workload weights, containing one weight for each execution slot. */
  public long[] getWeights() {
    return weights.clone();
  }

  /** Returns the name of the scenario document executed by this step. */
  public String getScenario() {
    return scenario;
  }

  public Map<String, String> getDocuments() {
    return Collections.unmodifiableMap(documents);
  }

  /** Returns the Pherf command-line options that each slot forwards verbatim to Pherf. */
  public List<String> getArgs() {
    return Collections.unmodifiableList(args);
  }

  /**
   * Returns the aggregate fleet peak rate in operations per second, or null if no rate limit is
   * specified.
   */
  public Long getRate() {
    return rate;
  }

  /** Returns the execution duration for each slot in milliseconds (ms), or null if unspecified. */
  public Long getDurationMs() {
    return durationMs;
  }

  /** Returns the aggregate fleet concurrency across all slots, or null if unspecified. */
  public Long getThreads() {
    return threads;
  }

  /** Returns the aggregate fleet row count across all slots, or null if unspecified. */
  public Long getRows() {
    return rows;
  }

  public Map<String, String> getProperties() {
    return Collections.unmodifiableMap(properties);
  }
}
