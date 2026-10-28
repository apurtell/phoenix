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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.Test;

public class AttributeModelTest {

  private static final Pattern DOC_ID = Pattern.compile("[0-9a-f]{8}[0-9]{12}");
  private static final Set<String> WORDS =
    new HashSet<>(Arrays.asList("vector", "index", "region", "query", "graph", "centroid",
      "segment", "tenant", "phoenix", "table", "scan", "probe", "recall", "latency", "cluster",
      "embedding", "document", "passage", "search", "filter", "server", "client", "mutation",
      "commit", "row", "column", "family", "split", "merge", "compaction"));

  private static AttributeModel model(long seed) {
    return new AttributeModel(new RandomStreams(seed), 8, 0.0, 100, 1000, 16, 0.0, 64);
  }

  @Test
  public void testVocabularies() {
    AttributeModel m = new AttributeModel(new RandomStreams(1L), 5, 1.5, 200, 300, 12, 0.7, 10);
    assertEquals("t", m.tenants().prefix());
    assertEquals(5, m.tenants().size());
    assertEquals(1.5, m.tenants().exponent(), 0.0);
    assertEquals("s", m.sources().prefix());
    assertEquals(200, m.sources().size());
    assertEquals(0.7, m.sources().exponent(), 0.0);
    assertEquals("c", m.categories().prefix());
    assertEquals(300, m.categories().size());
    assertEquals(0.7, m.categories().exponent(), 0.0);
    assertEquals("acl", m.aclTags().prefix());
    assertEquals(12, m.aclTags().size());
    assertEquals(0.7, m.aclTags().exponent(), 0.0);
  }

  @Test
  public void testParameters() {
    AttributeModel m = new AttributeModel(new RandomStreams(1L), 5, 1.5, 200, 300, 12, 0.7, 10);
    Map<String, String> p = m.parameters();
    assertEquals(Arrays.asList("tenants", "tenant.skew", "sources", "categories", "acl_tags",
      "filter.skew", "body.bytes"), Arrays.asList(p.keySet().toArray(new String[0])));
    assertEquals("5", p.get("tenants"));
    assertEquals("1.5", p.get("tenant.skew"));
    assertEquals("200", p.get("sources"));
    assertEquals("300", p.get("categories"));
    assertEquals("12", p.get("acl_tags"));
    assertEquals("0.7", p.get("filter.skew"));
    assertEquals("10", p.get("body.bytes"));
  }

  @Test
  public void testAttributesAreWellFormed() {
    AttributeModel m = model(42L);
    for (long i = 0; i < 2000; i++) {
      DocAttributes a = m.attributes(i);
      assertEquals(i, a.ordinal());
      assertTrue(a.tenant() >= 0 && a.tenant() < 8);
      assertEquals(m.tenants().term(a.tenant()), a.tenantId());
      assertEquals(AttributeModel.docId(i), a.docId());
      assertTrue(a.source() >= 0 && a.source() < 100);
      assertTrue(a.category() >= 0 && a.category() < 1000);
      assertTrue(a.aclTag() >= 0 && a.aclTag() < 16);
      long base = AttributeModel.CREATED_AT_EPOCH + i * AttributeModel.CREATED_AT_STEP_MS;
      assertTrue(a.createdAtMillis() >= base && a.createdAtMillis() < base + 1000);
      assertEquals(64, a.body().length());
      for (String w : a.body().trim().split(" ")) {
        assertTrue(w, WORDS.contains(w) || isPrefixOfWord(w));
      }
    }
  }

  private static boolean isPrefixOfWord(String w) {
    for (String word : WORDS) {
      if (word.startsWith(w)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void testCreatedAtIsIncreasing() {
    AttributeModel m = model(3L);
    long prev = Long.MIN_VALUE;
    for (long i = 0; i < 1000; i++) {
      long t = m.attributes(i).createdAtMillis();
      assertTrue(t > prev);
      prev = t;
    }
    assertEquals(1577836800000L, AttributeModel.CREATED_AT_EPOCH);
  }

  @Test
  public void testEmptyBody() {
    AttributeModel m = new AttributeModel(new RandomStreams(1L), 1, 0.0, 10, 10, 4, 0.0, 0);
    assertEquals("", m.attributes(5).body());
    AttributeModel one = new AttributeModel(new RandomStreams(1L), 1, 0.0, 10, 10, 4, 0.0, 1);
    assertEquals(1, one.attributes(5).body().length());
  }

  @Test
  public void testDeterminism() {
    AttributeModel a = model(42L);
    AttributeModel b = model(42L);
    for (long i = 0; i < 500; i++) {
      assertEquals(a.attributes(i), b.attributes(i));
      assertEquals(a.attributes(i).body(), b.attributes(i).body());
    }
    // Order and thread independence
    DocAttributes[] parallel = new DocAttributes[500];
    IntStream.range(0, 500).parallel().forEach(i -> parallel[i] = b.attributes(499 - i));
    for (int i = 0; i < 500; i++) {
      assertEquals(a.attributes(499 - i), parallel[i]);
    }
    assertEquals(a.attributes(123456789L), model(42L).attributes(123456789L));
  }

  @Test
  public void testSeedsChangeAttributes() {
    AttributeModel a = model(42L);
    AttributeModel b = model(43L);
    int sameCategory = 0;
    int sameBody = 0;
    for (long i = 0; i < 1000; i++) {
      DocAttributes x = a.attributes(i);
      DocAttributes y = b.attributes(i);
      assertEquals(x.docId(), y.docId());
      if (x.category() == y.category()) {
        sameCategory++;
      }
      if (x.body().equals(y.body())) {
        sameBody++;
      }
    }
    // Chance agreement of categories is 1 in 1000
    assertTrue(sameCategory < 10);
    assertEquals(0, sameBody);
  }

  @Test
  public void testDocIdFormat() {
    for (long ordinal : new long[] { 0L, 1L, 42L, 999_999L, 123_456_789_012L, 999_999_999_999L }) {
      String id = AttributeModel.docId(ordinal);
      assertEquals(20, id.length());
      assertTrue(id, DOC_ID.matcher(id).matches());
      assertEquals(ordinal, Long.parseLong(id.substring(AttributeModel.DOC_ID_HASH_DIGITS)));
      assertEquals(String.format(Locale.ROOT, "%08x", AttributeModel.docIdHash(ordinal)),
        id.substring(0, AttributeModel.DOC_ID_HASH_DIGITS));
    }
    assertTrue(AttributeModel.docId(42L).endsWith("000000000042"));
  }

  @Test
  public void testDocIdsAreUnique() {
    Set<String> ids = new HashSet<>();
    Set<Long> hashes = new HashSet<>();
    for (long i = 0; i < 100_000; i++) {
      assertTrue(ids.add(AttributeModel.docId(i)));
      hashes.add(AttributeModel.docIdHash(i));
    }
    // 32-bit hashes of 100k ordinals collide only rarely
    assertTrue(hashes.size() > 99_990);
  }

  @Test
  public void testDocIdHashRange() {
    for (long i = 0; i < 100_000; i++) {
      long h = AttributeModel.docIdHash(i);
      assertTrue(h >= 0 && h < (1L << 32));
    }
  }

  @Test
  public void testDocIdHashIsUniformOverSixteenBuckets() {
    int n = 160_000;
    long[] buckets = new long[16];
    for (long i = 0; i < n; i++) {
      buckets[(int) (AttributeModel.docIdHash(i) >>> 28)]++;
    }
    double expected = n / 16.0;
    double chi2 = 0.0;
    for (long c : buckets) {
      chi2 += (c - expected) * (c - expected) / expected;
      assertEquals(expected, c, expected * 0.05);
    }
    // 15 degrees of freedom; the 0.9999 quantile is about 44.3.
    assertTrue("chi-square " + chi2, chi2 < 44.3);
    // DOC_IDs of consecutive ordinals spread over the buckets: the first hex digit of 16
    // consecutive ordinals takes many values.
    Set<Character> firstDigits = new HashSet<>();
    for (long i = 1000; i < 1016; i++) {
      firstDigits.add(AttributeModel.docId(i).charAt(0));
    }
    assertTrue(firstDigits.size() >= 6);
  }

  @Test
  public void testDocIdBound() {
    assertEquals("00000000", AttributeModel.docIdBound(0.0));
    assertEquals("00000000", AttributeModel.docIdBound(-0.5));
    assertEquals("~", AttributeModel.docIdBound(1.0));
    assertEquals("~", AttributeModel.docIdBound(2.0));
    assertEquals("80000000", AttributeModel.docIdBound(0.5));
    assertEquals("40000000", AttributeModel.docIdBound(0.25));
    assertEquals("c0000000", AttributeModel.docIdBound(0.75));
    assertEquals("ffffffff", AttributeModel.docIdBound(Math.nextDown(1.0)));
    String prev = AttributeModel.docIdBound(0.0);
    for (int i = 1; i <= 1000; i++) {
      String b = AttributeModel.docIdBound(i / 1000.0);
      assertTrue(b + " after " + prev, b.compareTo(prev) > 0);
      if (i < 1000) {
        assertEquals(8, b.length());
      }
      prev = b;
    }
    // Every DOC_ID sorts below the upper bound
    assertTrue(AttributeModel.docId(999_999_999_999L).compareTo("~") < 0);
    assertTrue("ffffffff999999999999".compareTo("~") < 0);
  }

  @Test
  public void testDocIdBoundSelectsTheShareOfRows() {
    int n = 100_000;
    String[] ids = new String[n];
    for (int i = 0; i < n; i++) {
      ids[i] = AttributeModel.docId(i);
    }
    for (double f : new double[] { 0.0, 0.01, 0.1, 0.25, 0.5, 0.9, 1.0 }) {
      String bound = AttributeModel.docIdBound(f);
      int below = 0;
      for (String id : ids) {
        if (id.compareTo(bound) < 0) {
          below++;
        }
      }
      // Standard deviation of the share is at most 0.0016
      assertEquals("fraction " + f, f, (double) below / n, 0.008);
    }
    // A range [low, high) selects the share between the bounds
    String low = AttributeModel.docIdBound(0.3);
    String high = AttributeModel.docIdBound(0.55);
    int inside = 0;
    for (String id : ids) {
      if (id.compareTo(low) >= 0 && id.compareTo(high) < 0) {
        inside++;
      }
    }
    assertEquals(0.25, (double) inside / n, 0.008);
  }

  @Test
  public void testAttributeSelectivityMatchesVocabularyMass() {
    AttributeModel m = new AttributeModel(new RandomStreams(9L), 4, 1.0, 100, 200, 16, 1.0, 0);
    int n = 50_000;
    int[] categoryRange = m.categories().rangeForSelectivity(0.1, 17);
    int[] sourceRange = m.sources().rangeForSelectivity(0.05, 3);
    int inCategory = 0;
    int inSource = 0;
    int tenantZero = 0;
    for (int i = 0; i < n; i++) {
      DocAttributes a = m.attributes(i);
      if (a.category() >= categoryRange[0] && a.category() < categoryRange[1]) {
        inCategory++;
      }
      if (a.source() >= sourceRange[0] && a.source() < sourceRange[1]) {
        inSource++;
      }
      if (a.tenant() == 0) {
        tenantZero++;
      }
    }
    double pc = m.categories().rangeProbability(categoryRange[0], categoryRange[1]);
    double ps = m.sources().rangeProbability(sourceRange[0], sourceRange[1]);
    double pt = m.tenants().probability(0);
    assertEquals(pc, (double) inCategory / n, 4 * Math.sqrt(pc * (1 - pc) / n));
    assertEquals(ps, (double) inSource / n, 4 * Math.sqrt(ps * (1 - ps) / n));
    assertEquals(pt, (double) tenantZero / n, 4 * Math.sqrt(pt * (1 - pt) / n));
  }

  @Test
  public void testUpdatedTerms() {
    AttributeModel m = model(42L);
    DocAttributes current = m.attributes(77L);
    DocAttributes u1 = m.updatedTerms(current, 1L);
    assertEquals(u1, model(42L).updatedTerms(current, 1L));
    assertEquals(current.ordinal(), u1.ordinal());
    assertEquals(current.tenant(), u1.tenant());
    assertEquals(current.tenantId(), u1.tenantId());
    assertEquals(current.docId(), u1.docId());
    assertEquals(current.createdAtMillis(), u1.createdAtMillis());
    assertEquals(current.body(), u1.body());
    Set<List<Integer>> terms = new HashSet<>();
    for (long seq = 0; seq < 50; seq++) {
      DocAttributes u = m.updatedTerms(current, seq);
      assertTrue(u.source() >= 0 && u.source() < 100);
      assertTrue(u.category() >= 0 && u.category() < 1000);
      assertTrue(u.aclTag() >= 0 && u.aclTag() < 16);
      terms.add(Arrays.asList(u.source(), u.category(), u.aclTag()));
    }
    assertTrue(terms.size() > 45);
    assertNotEquals(m.updatedTerms(current, 1L), m.updatedTerms(current, 2L));
  }
}
