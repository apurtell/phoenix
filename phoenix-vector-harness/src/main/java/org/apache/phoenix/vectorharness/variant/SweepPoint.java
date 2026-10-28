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
package org.apache.phoenix.vectorharness.variant;

/**
 * One setting of query breadth: a VECTOR_PROBE_COUNT for IVF, an HNSW_EF_SEARCH for HNSW, or the
 * exact plan. The hint text goes inside the query's hint comment.
 */
public final class SweepPoint {

  private final String parameter;
  private final int value;
  private final String label;
  private final String hint;

  public SweepPoint(String parameter, int value, String label, String hint) {
    this.parameter = parameter;
    this.value = value;
    this.label = label;
    this.hint = hint;
  }

  /** Returns the name of the swept parameter, such as probes or ef_search. */
  public String parameter() {
    return parameter;
  }

  /** Returns the value of the parameter, or 0 when the engine default applies. */
  public int value() {
    return value;
  }

  /** Returns a label for reports, such as probes=16. */
  public String label() {
    return label;
  }

  /** Returns the hint text, without the comment delimiters; may be empty. */
  public String hint() {
    return hint;
  }

  @Override
  public String toString() {
    return label;
  }
}
