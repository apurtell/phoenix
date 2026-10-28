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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.bson.BinaryVector;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonString;

/**
 * Writes rows of both layouts over one connection, with prepared statements that the caller reuses.
 * Every row goes to every provisioned layout, so the layouts hold the same stream. The connection
 * must not auto commit; the caller commits.
 */
public final class RowWriter implements AutoCloseable {

  private final Connection conn;
  private final AttributeModel attributes;
  private final Map<TableLayout, PreparedStatement> upserts = new EnumMap<>(TableLayout.class);
  private final Map<TableLayout, PreparedStatement> attributeUpdates =
    new EnumMap<>(TableLayout.class);
  private final Map<TableLayout, PreparedStatement> deletes = new EnumMap<>(TableLayout.class);

  public RowWriter(Connection conn, TableNames names, Set<TableLayout> layouts,
    AttributeModel attributes) throws SQLException {
    this.conn = conn;
    this.attributes = attributes;
    for (TableLayout l : layouts) {
      String t = names.fullTable(l);
      if (l == TableLayout.CORPUS) {
        upserts.put(l,
          conn.prepareStatement("UPSERT INTO " + t + " (TENANT_ID, DOC_ID, SOURCE,"
            + " CATEGORY, ACL_TAG, CREATED_AT, BODY, " + Schema.EMBEDDING + ") VALUES"
            + " (?, ?, ?, ?, ?, ?, ?, ?)"));
        attributeUpdates.put(l, conn.prepareStatement("UPSERT INTO " + t + " (TENANT_ID, DOC_ID,"
          + " SOURCE, CATEGORY, ACL_TAG) VALUES (?, ?, ?, ?, ?)"));
      } else {
        PreparedStatement ps = conn.prepareStatement(
          "UPSERT INTO " + t + " (TENANT_ID, DOC_ID, " + Schema.PROFILE + ") VALUES (?, ?, ?)");
        upserts.put(l, ps);
        // The vector and the metadata share one document, so an attribute update rewrites it.
        attributeUpdates.put(l, ps);
      }
      deletes.put(l,
        conn.prepareStatement("DELETE FROM " + t + " WHERE TENANT_ID = ? AND DOC_ID = ?"));
    }
  }

  public Connection connection() {
    return conn;
  }

  /** Writes a whole row to every layout. */
  public void upsert(DocAttributes a, float[] vector) throws SQLException {
    for (Map.Entry<TableLayout, PreparedStatement> e : upserts.entrySet()) {
      PreparedStatement ps = e.getValue();
      ps.setString(1, a.tenantId());
      ps.setString(2, a.docId());
      if (e.getKey() == TableLayout.CORPUS) {
        ps.setString(3, attributes.sources().term(a.source()));
        ps.setString(4, attributes.categories().term(a.category()));
        ps.setString(5, attributes.aclTags().term(a.aclTag()));
        ps.setTimestamp(6, new Timestamp(a.createdAtMillis()));
        ps.setString(7, a.body());
        ps.setArray(8, vectorArray(conn, vector));
      } else {
        ps.setObject(3, profile(a, vector));
      }
      ps.executeUpdate();
    }
  }

  /**
   * Writes new SOURCE, CATEGORY, and ACL_TAG values. DOC_CORPUS gets a partial row that leaves the
   * vector untouched; DOC_PROFILE gets its whole document, with the unchanged vector.
   */
  public void updateAttributes(DocAttributes a, float[] vector) throws SQLException {
    for (Map.Entry<TableLayout, PreparedStatement> e : attributeUpdates.entrySet()) {
      PreparedStatement ps = e.getValue();
      ps.setString(1, a.tenantId());
      ps.setString(2, a.docId());
      if (e.getKey() == TableLayout.CORPUS) {
        ps.setString(3, attributes.sources().term(a.source()));
        ps.setString(4, attributes.categories().term(a.category()));
        ps.setString(5, attributes.aclTags().term(a.aclTag()));
      } else {
        ps.setObject(3, profile(a, vector));
      }
      ps.executeUpdate();
    }
  }

  public void delete(DocAttributes a) throws SQLException {
    for (PreparedStatement ps : deletes.values()) {
      ps.setString(1, a.tenantId());
      ps.setString(2, a.docId());
      ps.executeUpdate();
    }
  }

  public void commit() throws SQLException {
    conn.commit();
  }

  /** Returns the BSON document of DOC_PROFILE for a row. */
  public BsonDocument profile(DocAttributes a, float[] vector) {
    BsonDocument doc = new BsonDocument();
    doc.put("category", new BsonString(attributes.categories().term(a.category())));
    doc.put("acl_tag", new BsonString(attributes.aclTags().term(a.aclTag())));
    doc.put("source", new BsonString(attributes.sources().term(a.source())));
    doc.put("search",
      new BsonDocument("embedding", new BsonBinary(BinaryVector.floatVector(vector))));
    return doc;
  }

  /** Binds a vector as a FLOAT array, the form that Phoenix coerces to a vector. */
  public static java.sql.Array vectorArray(Connection conn, float[] v) throws SQLException {
    Float[] boxed = new Float[v.length];
    for (int i = 0; i < v.length; i++) {
      boxed[i] = v[i];
    }
    return conn.createArrayOf("FLOAT", boxed);
  }

  @Override
  public void close() throws SQLException {
    SQLException first = null;
    for (Map<TableLayout, PreparedStatement> m : java.util.Arrays.asList(upserts, attributeUpdates,
      deletes)) {
      for (PreparedStatement ps : m.values()) {
        try {
          ps.close();
        } catch (SQLException e) {
          if (first == null) {
            first = e;
          }
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }
}
