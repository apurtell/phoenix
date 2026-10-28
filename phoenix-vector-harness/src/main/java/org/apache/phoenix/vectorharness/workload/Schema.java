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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;

/**
 * The DDL of the two layouts. DOC_CORPUS is keyed by (TENANT_ID, DOC_ID) and holds the relational
 * attributes, the unindexed BODY payload, and the native vector column EMBEDDING. DOC_PROFILE has
 * the same key and one BSON column PROFILE holding category, acl_tag, source, and the Float32
 * vector at search.embedding. Tables are pre-split at quantiles of a deterministic sample of their
 * row keys, so regions start balanced whatever the tenant skew; salted tables are pre-split by
 * their buckets instead.
 */
public final class Schema {

  /** The vector column of DOC_CORPUS. */
  public static final String EMBEDDING = "EMBEDDING";
  /** The BSON column of DOC_PROFILE. */
  public static final String PROFILE = "PROFILE";
  /** The BSON path of the vector of DOC_PROFILE. */
  public static final String PROFILE_VECTOR_PATH = "search.embedding";

  static final int SPLIT_SAMPLE = 100_000;

  private final HarnessConfig config;
  private final TableNames names;
  private final int dim;

  public Schema(HarnessConfig config, TableNames names, int dim) {
    this.config = config;
    this.names = names;
    this.dim = dim;
  }

  /** Returns the SQL expression of the indexed vector of a layout. */
  public String vectorExpression(TableLayout layout) {
    if (layout == TableLayout.CORPUS) {
      return EMBEDDING;
    }
    return "BSON_VECTOR_VALUE(" + PROFILE + ", '" + PROFILE_VECTOR_PATH + "', " + dim + ")";
  }

  /** Returns the SQL expression of an attribute of a layout, such as CATEGORY. */
  public String attributeExpression(TableLayout layout, String attribute) {
    if (layout == TableLayout.CORPUS) {
      return attribute;
    }
    return "BSON_VALUE(" + PROFILE + ", '" + attribute.toLowerCase(Locale.ROOT) + "', 'VARCHAR')";
  }

  /** Returns the CREATE TABLE statement of a layout, with one bind per split point. */
  public String createTable(TableLayout layout, int splitPoints) {
    StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
    sb.append(names.fullTable(layout))
      .append(" (TENANT_ID VARCHAR NOT NULL, DOC_ID VARCHAR NOT NULL");
    if (layout == TableLayout.CORPUS) {
      sb.append(", SOURCE VARCHAR, CATEGORY VARCHAR, ACL_TAG VARCHAR, CREATED_AT TIMESTAMP,")
        .append(" BODY VARCHAR, ").append(EMBEDDING).append(" VECTOR(FLOAT, ").append(dim)
        .append(')');
    } else {
      sb.append(", ").append(PROFILE).append(" BSON");
    }
    sb.append(" CONSTRAINT PK PRIMARY KEY (TENANT_ID, DOC_ID))");
    List<String> props = new ArrayList<>();
    if (config.multiTenant()) {
      props.add("MULTI_TENANT=true");
    }
    if (config.saltBuckets() > 0) {
      props.add("SALT_BUCKETS=" + config.saltBuckets());
    }
    if (config.storageScheme().equals("SINGLE_CELL_ARRAY_WITH_OFFSETS")) {
      props.add("IMMUTABLE_ROWS=true");
      props.add("IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS");
    }
    if (!props.isEmpty()) {
      sb.append(' ').append(String.join(", ", props));
    }
    if (splitPoints > 0) {
      sb.append(" SPLIT ON (");
      for (int i = 0; i < splitPoints; i++) {
        sb.append(i == 0 ? "?" : ", ?");
      }
      sb.append(')');
    }
    return sb.toString();
  }

  /** Creates the table of a layout, pre-split at the given points. */
  public void create(Connection conn, TableLayout layout, List<byte[]> splits) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(createTable(layout, splits.size()))) {
      for (int i = 0; i < splits.size(); i++) {
        ps.setBytes(i + 1, splits.get(i));
      }
      ps.execute();
    }
  }

  public void drop(Connection conn, TableLayout layout) throws SQLException {
    conn.createStatement().execute("DROP TABLE IF EXISTS " + names.fullTable(layout) + " CASCADE");
  }

  /** Returns the row key of a row, as Phoenix encodes the unsalted (VARCHAR, VARCHAR) key. */
  public static byte[] rowKey(DocAttributes a) {
    byte[] t = a.tenantId().getBytes(StandardCharsets.UTF_8);
    byte[] d = a.docId().getBytes(StandardCharsets.UTF_8);
    byte[] key = new byte[t.length + 1 + d.length];
    System.arraycopy(t, 0, key, 0, t.length);
    key[t.length] = 0;
    System.arraycopy(d, 0, key, t.length + 1, d.length);
    return key;
  }

  /**
   * Computes split points that divide the rows into equal shares: the quantiles of the row keys of
   * a deterministic sample of ordinals. Salted tables and single region tables get none.
   */
  public List<byte[]> splitPoints(Corpus corpus, int regions) {
    List<byte[]> out = new ArrayList<>();
    if (regions <= 1 || config.saltBuckets() > 0) {
      return out;
    }
    long rows = corpus.rows();
    int n = (int) Math.min(rows, SPLIT_SAMPLE);
    byte[][] keys = new byte[n][];
    for (int i = 0; i < n; i++) {
      long ordinal = n == rows ? i : (long) ((double) i * rows / n);
      keys[i] = rowKey(corpus.attributes(ordinal));
    }
    Arrays.sort(keys, Bytes.BYTES_COMPARATOR);
    byte[] last = null;
    for (int r = 1; r < regions; r++) {
      byte[] k = keys[(int) ((long) r * n / regions)];
      if (last == null || Bytes.compareTo(k, last) > 0) {
        out.add(k);
        last = k;
      }
    }
    return out;
  }
}
