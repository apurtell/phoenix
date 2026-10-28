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
 * Synthesizes the relational attributes of every row as a function of the row ordinal, for both
 * synthetic and published corpora. Attributes are independent of the vectors, so the selectivity of
 * a predicate over them is the analytic probability mass of its vocabulary terms.
 * <p>
 * DOC_ID starts with eight hex digits of a hash of the ordinal, followed by the zero padded
 * ordinal, so that document identifiers spread uniformly over pre-split region boundaries and a
 * DOC_ID range covers a known share of the rows.
 */
public final class AttributeModel {

  /** Epoch of CREATED_AT: 2020-01-01T00:00:00Z. */
  public static final long CREATED_AT_EPOCH = 1577836800000L;
  /** Spacing of CREATED_AT between consecutive ordinals. */
  public static final long CREATED_AT_STEP_MS = 1000L;
  /** Hex digits of the hash prefix of DOC_ID. */
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

  /** Returns the attributes of a row. */
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
   * Draws new SOURCE, CATEGORY, and ACL_TAG terms for an attribute update of a row. The update
   * sequence number makes each update of the same row draw different terms.
   */
  public DocAttributes updatedTerms(DocAttributes current, long updateSeq) {
    SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, 0x41545452L, updateSeq);
    return current.withTerms(sources.sample(rnd), categories.sample(rnd), aclTags.sample(rnd));
  }

  /** Returns the DOC_ID of a row ordinal. */
  public static String docId(long ordinal) {
    return String.format(Locale.ROOT, "%08x%012d", docIdHash(ordinal), ordinal);
  }

  /** Returns the 32-bit hash that prefixes the DOC_ID of a row ordinal. */
  public static long docIdHash(long ordinal) {
    return SplitMix64.mix64(ordinal * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L) >>> 32;
  }

  /** Returns the eight hex digit DOC_ID prefix that starts the given share of the hash space. */
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

  /** Returns the parameters that define the attribute stream, for fingerprints and manifests. */
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
