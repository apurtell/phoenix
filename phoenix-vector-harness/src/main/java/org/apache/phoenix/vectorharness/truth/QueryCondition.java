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
package org.apache.phoenix.vectorharness.truth;

import java.util.Locale;
import java.util.Objects;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;

/**
 * The relational part of one panel query: a conjunction of optional predicates over the attributes,
 * evaluated against the client-side attribute store for ground truth and rendered as SQL by the
 * workload. The predicates are an optional tenant equality, an optional CATEGORY or SOURCE
 * vocabulary range, an optional DOC_ID range, and an optional distance radius. A condition is
 * identified by its key, which is unique within one panel query.
 */
public final class QueryCondition {

  /** The attribute that a vocabulary range predicate constrains. */
  public enum Attribute {
    NONE,
    CATEGORY,
    SOURCE
  }

  private final QueryShape shape;
  private final int tenant;
  private final Attribute attribute;
  private final int termFrom;
  private final int termTo;
  private final String docIdLow;
  private final String docIdHigh;
  private final double radius;
  private final int k;
  private final double selectivity;
  private final String family;
  private final String key;

  private QueryCondition(Builder b) {
    this.shape = b.shape;
    this.tenant = b.tenant;
    this.attribute = b.attribute;
    this.termFrom = b.termFrom;
    this.termTo = b.termTo;
    this.docIdLow = b.docIdLow;
    this.docIdHigh = b.docIdHigh;
    this.radius = b.radius;
    this.k = b.k;
    this.selectivity = b.selectivity;
    this.family = b.family != null ? b.family : b.shape.id();
    this.key = buildKey();
  }

  public static Builder builder(QueryShape shape) {
    return new Builder(shape);
  }

  public QueryShape shape() {
    return shape;
  }

  /** Returns the tenant ordinal of a tenant predicate, or -1. */
  public int tenant() {
    return tenant;
  }

  public Attribute attribute() {
    return attribute;
  }

  /** Returns the first vocabulary ordinal of the range, inclusive. */
  public int termFrom() {
    return termFrom;
  }

  /** Returns the last vocabulary ordinal of the range, exclusive. */
  public int termTo() {
    return termTo;
  }

  /** Returns the inclusive lower DOC_ID bound, or null. */
  public String docIdLow() {
    return docIdLow;
  }

  /** Returns the exclusive upper DOC_ID bound, or null. */
  public String docIdHigh() {
    return docIdHigh;
  }

  /** Returns the distance radius of a strict distance predicate, or NaN. */
  public double radius() {
    return radius;
  }

  public boolean hasRadius() {
    return !Double.isNaN(radius);
  }

  /** Returns the k that a distance-bounded condition was sized for, or 0. */
  public int k() {
    return k;
  }

  /** Returns the analytic selectivity of the relational predicates, before the radius. */
  public double selectivity() {
    return selectivity;
  }

  /**
   * Returns the family of the condition: the shape, with the target selectivity for the shapes that
   * sweep it and the k for distance-bounded conditions, such as covered_filter@0.01. The conditions
   * of one family differ between panel queries only in their parameters, so the harness aggregates
   * measurements by family.
   */
  public String family() {
    return family;
  }

  /** Returns true if the condition has no predicate at all. */
  public boolean isUnconstrained() {
    return tenant < 0 && attribute == Attribute.NONE && docIdLow == null && docIdHigh == null
      && !hasRadius();
  }

  /** Returns the key of the condition, such as {@code covered_filter/category[10,20)}. */
  public String key() {
    return key;
  }

  /** Evaluates the relational predicates. The radius is checked by the caller on distances. */
  public boolean test(DocAttributes a) {
    if (tenant >= 0 && a.tenant() != tenant) {
      return false;
    }
    switch (attribute) {
      case CATEGORY:
        if (a.category() < termFrom || a.category() >= termTo) {
          return false;
        }
        break;
      case SOURCE:
        if (a.source() < termFrom || a.source() >= termTo) {
          return false;
        }
        break;
      default:
        break;
    }
    if (docIdLow != null && a.docId().compareTo(docIdLow) < 0) {
      return false;
    }
    return docIdHigh == null || a.docId().compareTo(docIdHigh) < 0;
  }

  /** Evaluates the radius predicate on a distance. */
  public boolean withinRadius(double distance) {
    return !hasRadius() || distance < radius;
  }

  private String buildKey() {
    StringBuilder sb = new StringBuilder(shape.id());
    if (tenant >= 0) {
      sb.append("/tenant=").append(tenant);
    }
    if (attribute != Attribute.NONE) {
      sb.append('/').append(attribute.name().toLowerCase(Locale.ROOT)).append('[').append(termFrom)
        .append(',').append(termTo).append(')');
    }
    if (docIdLow != null || docIdHigh != null) {
      sb.append("/doc[").append(docIdLow == null ? "" : docIdLow).append(',')
        .append(docIdHigh == null ? "" : docIdHigh).append(')');
    }
    if (hasRadius()) {
      sb.append("/radius=").append(Double.doubleToLongBits(radius)).append("/k=").append(k);
    }
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof QueryCondition && ((QueryCondition) o).key.equals(key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }

  @Override
  public String toString() {
    return key;
  }

  /** Builds conditions. */
  public static final class Builder {
    private final QueryShape shape;
    private int tenant = -1;
    private Attribute attribute = Attribute.NONE;
    private int termFrom;
    private int termTo;
    private String docIdLow;
    private String docIdHigh;
    private double radius = Double.NaN;
    private int k;
    private double selectivity = 1.0;
    private String family;

    private Builder(QueryShape shape) {
      this.shape = Objects.requireNonNull(shape);
    }

    public Builder tenant(int t) {
      this.tenant = t;
      return this;
    }

    public Builder range(Attribute attr, int from, int to) {
      this.attribute = attr;
      this.termFrom = from;
      this.termTo = to;
      return this;
    }

    public Builder docIds(String low, String high) {
      this.docIdLow = low;
      this.docIdHigh = high;
      return this;
    }

    public Builder radius(double r, int forK) {
      this.radius = r;
      this.k = forK;
      return this;
    }

    public Builder selectivity(double s) {
      this.selectivity = s;
      return this;
    }

    public Builder family(String f) {
      this.family = f;
      return this;
    }

    public QueryCondition build() {
      return new QueryCondition(this);
    }
  }
}
