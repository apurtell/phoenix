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
package org.apache.phoenix.vectorharness.config;

import java.util.Locale;

/**
 * The index variant that one run evaluates. EXACT builds no vector index, so queries run the exact
 * distributed top-N plan; it is the performance baseline and the cluster-side check of the ground
 * truth. IVF builds a global inverted file index and HNSW builds a region-local graph index.
 */
public enum VariantKind {
  EXACT,
  IVF,
  HNSW;

  /** Returns the lower case name used in profiles, table names, and run identifiers. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static VariantKind parse(String text) {
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Unknown variant: " + text + " (expected exact, ivf, or hnsw)");
    }
  }
}
