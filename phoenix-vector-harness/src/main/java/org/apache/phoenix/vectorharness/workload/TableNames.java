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
package org.apache.phoenix.vectorharness.workload;

import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.TableLayout;

/**
 * Resolves qualified table, index, and HBase snapshot names for harness runs. Incorporates unique
 * configuration suffixes to prevent naming collisions.
 */
public final class TableNames {

  private final String schema;
  private final String suffix;

  public TableNames(String schema, String suffix) {
    this.schema = HarnessConfig.sanitizeIdentifier(schema);
    this.suffix = HarnessConfig.sanitizeIdentifier(suffix);
  }

  public static TableNames of(HarnessConfig config) {
    return new TableNames(config.schema(), config.tableSuffix());
  }

  public String schema() {
    return schema;
  }

  public String suffix() {
    return suffix;
  }

  /** Returns unqualified table name (e.g. DOC_CORPUS_HNSW_SQ8). */
  public String table(TableLayout layout) {
    return layout.baseName() + "_" + suffix;
  }

  /** Returns fully schema-qualified table name. */
  public String fullTable(TableLayout layout) {
    return schema + "." + table(layout);
  }

  /** Returns unqualified vector index name. */
  public String index(TableLayout layout) {
    return table(layout) + "_VIDX";
  }

  /** Returns fully schema qualified index name. */
  public String fullIndex(TableLayout layout) {
    return schema + "." + index(layout);
  }

  /** Returns HBase snapshot name for unindexed base table. */
  public String snapshot(TableLayout layout) {
    return "vh_" + schema.toLowerCase(java.util.Locale.ROOT) + "_"
      + table(layout).toLowerCase(java.util.Locale.ROOT) + "_base";
  }
}
