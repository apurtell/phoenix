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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines the specification for a single step of a run, including the required number of agents,
 * the row stride between slots, and the documents and properties provided to each slot prior to
 * token resolution.
 */
public final class StepSpec {
  private final int agents;
  private final long stride;
  private final Map<String, String> documents;
  private final Map<String, String> properties;

  public StepSpec(int agents, long stride, Map<String, String> documents,
    Map<String, String> properties) {
    this.agents = agents;
    this.stride = stride;
    this.documents = new LinkedHashMap<>(documents);
    this.properties = new LinkedHashMap<>(properties);
  }

  public int getAgents() {
    return agents;
  }

  public long getStride() {
    return stride;
  }

  public Map<String, String> getDocuments() {
    return Collections.unmodifiableMap(documents);
  }

  public Map<String, String> getProperties() {
    return Collections.unmodifiableMap(properties);
  }
}
