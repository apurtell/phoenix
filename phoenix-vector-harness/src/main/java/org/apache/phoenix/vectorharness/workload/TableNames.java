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
 * The names of the tables, indexes, and snapshots of one build-time configuration. Every name
 * carries the table suffix, so that configurations coexist in one cluster. Names are upper case
 * identifiers that need no quoting.
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

  /** Returns the table name without the schema, such as DOC_CORPUS_HNSW_SQ8. */
  public String table(TableLayout layout) {
    return layout.baseName() + "_" + suffix;
  }

  /** Returns the schema qualified table name. */
  public String fullTable(TableLayout layout) {
    return schema + "." + table(layout);
  }

  /** Returns the index name without the schema. */
  public String index(TableLayout layout) {
    return table(layout) + "_VIDX";
  }

  /** Returns the schema qualified index name, as SYSTEM.VECTOR_CENTROID records it. */
  public String fullIndex(TableLayout layout) {
    return schema + "." + index(layout);
  }

  /** Returns the name of the snapshot of the unindexed data table. */
  public String snapshot(TableLayout layout) {
    return "vh_" + schema.toLowerCase(java.util.Locale.ROOT) + "_"
      + table(layout).toLowerCase(java.util.Locale.ROOT) + "_base";
  }
}
