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
 * Configuration point for query breadth during parameter sweeps (e.g. VECTOR_PROBE_COUNT for IVF,
 * HNSW_EF_SEARCH for HNSW, or exact search).
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

  /** Returns name of the swept parameter (e.g. "probes" or "ef_search"). */
  public String parameter() {
    return parameter;
  }

  /** Returns value of the parameter, or 0 when using engine default. */
  public int value() {
    return value;
  }

  /** Returns report label (e.g. "probes=16"). */
  public String label() {
    return label;
  }

  /** Returns SQL hint text without comment delimiters, or empty string. */
  public String hint() {
    return hint;
  }

  @Override
  public String toString() {
    return label;
  }
}
