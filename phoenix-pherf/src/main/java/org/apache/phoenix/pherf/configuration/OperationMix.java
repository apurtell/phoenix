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

import javax.xml.bind.annotation.XmlAttribute;

/**
 * Represents a single entry in the operation mix of a query set, pairing a query kind with its
 * relative weight. Both attributes retain their raw XML text values during unmarshalling so that
 * mix validation can report descriptive error messages for invalid values rather than silently
 * coercing them to 0.
 */
public class OperationMix {
  private String kind;
  private String weight;

  /**
   * Returns the query kind targeted by this mix entry (SELECT, SCAN, or DELETE).
   */
  @XmlAttribute
  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  /**
   * Returns the relative weight assigned to this query kind, which must be an integer of at least
   * 1. Scheduled arrivals are distributed across query kinds in proportion to their weights.
   */
  @XmlAttribute
  public String getWeight() {
    return weight;
  }

  public void setWeight(String weight) {
    this.weight = weight;
  }
}
