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
package org.apache.phoenix.vectorharness.corpus;

import java.util.Objects;

/**
 * The relational attributes of one document row. The vocabulary ordinals of SOURCE, CATEGORY, and
 * ACL_TAG are kept beside the names, so that the client-side attribute store evaluates predicates
 * without parsing names. Instances are immutable; an attribute update makes a new instance.
 */
public final class DocAttributes {

  private final long ordinal;
  private final int tenant;
  private final String tenantId;
  private final String docId;
  private final int source;
  private final int category;
  private final int aclTag;
  private final long createdAtMillis;
  private final String body;

  public DocAttributes(long ordinal, int tenant, String tenantId, String docId, int source,
    int category, int aclTag, long createdAtMillis, String body) {
    this.ordinal = ordinal;
    this.tenant = tenant;
    this.tenantId = tenantId;
    this.docId = docId;
    this.source = source;
    this.category = category;
    this.aclTag = aclTag;
    this.createdAtMillis = createdAtMillis;
    this.body = body;
  }

  /** Returns the corpus ordinal of the row, which identifies it across phases. */
  public long ordinal() {
    return ordinal;
  }

  /** Returns the tenant ordinal. */
  public int tenant() {
    return tenant;
  }

  public String tenantId() {
    return tenantId;
  }

  public String docId() {
    return docId;
  }

  /** Returns the SOURCE vocabulary ordinal. */
  public int source() {
    return source;
  }

  /** Returns the CATEGORY vocabulary ordinal. */
  public int category() {
    return category;
  }

  /** Returns the ACL_TAG vocabulary ordinal. */
  public int aclTag() {
    return aclTag;
  }

  public long createdAtMillis() {
    return createdAtMillis;
  }

  public String body() {
    return body;
  }

  /** Returns a copy with new SOURCE, CATEGORY, and ACL_TAG ordinals. */
  public DocAttributes withTerms(int newSource, int newCategory, int newAclTag) {
    return new DocAttributes(ordinal, tenant, tenantId, docId, newSource, newCategory, newAclTag,
      createdAtMillis, body);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DocAttributes)) {
      return false;
    }
    DocAttributes that = (DocAttributes) o;
    return ordinal == that.ordinal && tenant == that.tenant && source == that.source
      && category == that.category && aclTag == that.aclTag
      && createdAtMillis == that.createdAtMillis && tenantId.equals(that.tenantId)
      && docId.equals(that.docId) && Objects.equals(body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ordinal, tenantId, docId, source, category, aclTag);
  }

  @Override
  public String toString() {
    return "Doc[" + tenantId + "/" + docId + " #" + ordinal + "]";
  }
}
