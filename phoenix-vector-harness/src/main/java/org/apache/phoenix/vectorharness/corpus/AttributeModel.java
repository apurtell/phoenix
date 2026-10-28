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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Generates deterministic document attributes (DOC_ID, TENANT_ID, SOURCE, CATEGORY, ACL_TAG,
 * CREATED_AT, BODY) as a function of row ordinal. DOC_ID values are prefixed with a uniform 32-bit
 * hash of the ordinal to distribute rows evenly across pre-split region boundaries.
 */
public final class AttributeModel {

  /** Base timestamp epoch (2020-01-01T00:00:00Z). */
  public static final long CREATED_AT_EPOCH = 1577836800000L;
  /** Millisecond increment between consecutive ordinals. */
  public static final long CREATED_AT_STEP_MS = 1000L;
  /** Length of DOC_ID hash prefix in hex digits. */
  public static final int DOC_ID_HASH_DIGITS = 8;

  private static final String[] WORDS = { "vector", "index", "region", "query", "graph", "centroid",
    "segment", "tenant", "phoenix", "table", "scan", "probe", "recall", "latency", "cluster",
    "embedding", "document", "passage", "search", "filter", "server", "client", "mutation",
    "commit", "row", "column", "family", "split", "merge", "compaction" };

  private final RandomStreams streams;
  private final long attributeSeed;
  private final Vocabulary tenants;
  private final Vocabulary sources;
  private final Vocabulary categories;
  private final Vocabulary aclTags;
  private final int bodyBytes;

  public AttributeModel(RandomStreams streams, int tenants, double tenantSkew, int sources,
    int categories, int aclTags, double filterSkew, int bodyBytes) {
    this.streams = streams;
    this.attributeSeed = streams.streamSeed(RandomStreams.ATTRIBUTES);
    this.tenants = new Vocabulary("t", tenants, tenantSkew);
    this.sources = new Vocabulary("s", sources, filterSkew);
    this.categories = new Vocabulary("c", categories, filterSkew);
    this.aclTags = new Vocabulary("acl", aclTags, filterSkew);
    this.bodyBytes = bodyBytes;
  }

  public Vocabulary tenants() {
    return tenants;
  }

  public Vocabulary sources() {
    return sources;
  }

  public Vocabulary categories() {
    return categories;
  }

  public Vocabulary aclTags() {
    return aclTags;
  }

  /** Generates attributes for the given row ordinal. */
  public DocAttributes attributes(long ordinal) {
    SplitMix64 rnd = RandomStreams.atSeed(attributeSeed, ordinal);
    int tenant = tenants.sample(rnd);
    int source = sources.sample(rnd);
    int category = categories.sample(rnd);
    int acl = aclTags.sample(rnd);
    long createdAt = CREATED_AT_EPOCH + ordinal * CREATED_AT_STEP_MS + rnd.nextInt(1000);
    String body = bodyBytes > 0 ? body(rnd) : "";
    return new DocAttributes(ordinal, tenant, tenants.term(tenant), docId(ordinal), source,
      category, acl, createdAt, body);
  }

  /**
   * Generates updated attribute terms for a mutated row given an update sequence number.
   */
  public DocAttributes updatedTerms(DocAttributes current, long updateSeq) {
    SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, 0x41545452L, updateSeq);
    return current.withTerms(sources.sample(rnd), categories.sample(rnd), aclTags.sample(rnd));
  }

  /** Formats the DOC_ID string for a row ordinal. */
  public static String docId(long ordinal) {
    return String.format(Locale.ROOT, "%08x%012d", docIdHash(ordinal), ordinal);
  }

  /** Computes the 32-bit hash prefix for a row ordinal. */
  public static long docIdHash(long ordinal) {
    return SplitMix64.mix64(ordinal * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L) >>> 32;
  }

  /** Returns the hex prefix corresponding to a fractional boundary of the DOC_ID keyspace. */
  public static String docIdBound(double fraction) {
    if (fraction <= 0.0) {
      return "00000000";
    }
    if (fraction >= 1.0) {
      return "~";
    }
    long v = (long) Math.floor(fraction * 4294967296.0);
    return String.format(Locale.ROOT, "%08x", v);
  }

  /** Returns attribute generation configuration parameters for manifests and digests. */
  public Map<String, String> parameters() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("tenants", Integer.toString(tenants.size()));
    m.put("tenant.skew", Double.toString(tenants.exponent()));
    m.put("sources", Integer.toString(sources.size()));
    m.put("categories", Integer.toString(categories.size()));
    m.put("acl_tags", Integer.toString(aclTags.size()));
    m.put("filter.skew", Double.toString(categories.exponent()));
    m.put("body.bytes", Integer.toString(bodyBytes));
    return m;
  }

  private String body(SplitMix64 rnd) {
    StringBuilder sb = new StringBuilder(bodyBytes + 16);
    while (sb.length() < bodyBytes) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(WORDS[rnd.nextInt(WORDS.length)]);
    }
    sb.setLength(bodyBytes);
    return sb.toString();
  }
}
