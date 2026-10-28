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

import java.util.Locale;

/**
 * Tells how a vector index serves a filter on a non-key column. Reports and checks use this value
 * so that an expectation depends on the real index definition and not on the query shape name.
 */
public enum FilterCoverage {
  /** The query has no filter on a non-key column. */
  NONE,
  /** The index includes the filter column, so the index rows answer the filter. */
  COVERED,
  /** The index does not include the filter column, so the query also reads the data rows. */
  UNCOVERED,
  /** The index is local to the data region and reads the filter column from the same region. */
  LOCAL,
  /** No vector index exists, so the query scans the data rows. */
  NO_INDEX;

  /** Returns the lower case identifier for reports and CSV files. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }
}
