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
 * Defines the specification for a run as an ordered sequence of steps that execute one after the
 * other. The configured seed is used to derive the data-plane identity for each slot.
 */
public final class RunSpec {
  private final long seed;
  private final List<StepSpec> steps;

  public RunSpec(long seed, List<StepSpec> steps) {
    this.seed = seed;
    this.steps = new ArrayList<>(steps);
  }

  public long getSeed() {
    return seed;
  }

  public List<StepSpec> getSteps() {
    return Collections.unmodifiableList(steps);
  }
}
