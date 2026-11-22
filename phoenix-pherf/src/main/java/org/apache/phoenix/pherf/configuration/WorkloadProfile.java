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
package org.apache.phoenix.pherf.configuration;

import java.util.List;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Represents a reusable workload profile configuration file defining an open loop rate curve and an
 * operation mix that can be referenced by a query set in any scenario. A querySet references the
 * file through its profile attribute, and scenario parsing copies the open loop schedule and
 * operation mix from the profile into the target query set while preserving the scenario's own
 * queries, concurrency, warmup, properties, DDL, co-generation, and rows.
 */
@XmlRootElement(name = "workloadProfile")
public class WorkloadProfile {
  private OpenLoop openLoop;
  private List<OperationMix> mix;

  /** Returns the open loop rate curve, which is required in every workload profile. */
  public OpenLoop getOpenLoop() {
    return openLoop;
  }

  public void setOpenLoop(OpenLoop openLoop) {
    this.openLoop = openLoop;
  }

  /**
   * Returns the operation mix keyed by query kind, or null when absent, in which case the queries
   * execute sequentially in turn.
   */
  @XmlElementWrapper(name = "mix")
  @XmlElement(name = "operation")
  public List<OperationMix> getMix() {
    return mix;
  }

  public void setMix(List<OperationMix> mix) {
    this.mix = mix;
  }
}
