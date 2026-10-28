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

import static org.apache.phoenix.vectorharness.truth.TruthTestSupport.assertListEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GroundTruthTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private static NeighborList list(boolean complete, long[] o, double[] d) {
    return new NeighborList(o, d, complete);
  }

  private static GroundTruth sample() {
    List<Map<String, NeighborList>> lists = new ArrayList<>();
    Map<String, NeighborList> q0 = new LinkedHashMap<>();
    q0.put("unfiltered", list(false, new long[] { 5, 3, 9 }, new double[] { 0.1, 0.2, 0.2 }));
    q0.put("covered_filter/category[10,20)", list(true, new long[] { 3 }, new double[] { 0.2 }));
    q0.put("tenant_scoped/tenant=99", NeighborList.empty());
    lists.add(q0);
    Map<String, NeighborList> q1 = new LinkedHashMap<>();
    // Inner product distances are negative; extreme values must survive the round trip.
    q1.put("unfiltered", list(false, new long[] { Long.MAX_VALUE, 0, 1L << 40 },
      new double[] { -1e300, -0.0, Double.MAX_VALUE }));
    q1.put("distance_bound/radius=" + Double.doubleToLongBits(0.5) + "/k=10",
      list(true, new long[] { 7, 8 }, new double[] { Double.MIN_VALUE, 0.49999 }));
    lists.add(q1);
    lists.add(new LinkedHashMap<>());
    return new GroundTruth("fp-0123", "panel-abcd", 1_000_000L, "COSINE", 201, lists);
  }

  private static void assertTruthEquals(GroundTruth expected, GroundTruth actual) {
    assertEquals(expected.fingerprint(), actual.fingerprint());
    assertEquals(expected.panelDigest(), actual.panelDigest());
    assertEquals(expected.rows(), actual.rows());
    assertEquals(expected.metric(), actual.metric());
    assertEquals(expected.capacity(), actual.capacity());
    assertEquals(expected.queries(), actual.queries());
    for (int q = 0; q < expected.queries(); q++) {
      Map<String, NeighborList> e = expected.forQuery(q);
      Map<String, NeighborList> a = actual.forQuery(q);
      // Keys keep their order, which is the order of the conditions.
      assertEquals(new ArrayList<>(e.keySet()), new ArrayList<>(a.keySet()));
      for (String key : e.keySet()) {
        assertListEquals("query " + q + " " + key, e.get(key), a.get(key));
      }
    }
  }

  @Test
  public void testAccessors() {
    GroundTruth t = sample();
    assertEquals("fp-0123", t.fingerprint());
    assertEquals("panel-abcd", t.panelDigest());
    assertEquals(1_000_000L, t.rows());
    assertEquals("COSINE", t.metric());
    assertEquals(201, t.capacity());
    assertEquals(3, t.queries());
    assertEquals(3, t.get(0, "unfiltered").size());
    assertEquals(5, t.get(0, "unfiltered").ordinal(0));
    assertNull(t.get(0, "no such key"));
    assertNull(t.get(-1, "unfiltered"));
    assertNull(t.get(3, "unfiltered"));
    assertTrue(t.forQuery(2).isEmpty());
  }

  @Test
  public void testRoundTrip() throws IOException {
    GroundTruth t = sample();
    Path file = folder.getRoot().toPath().resolve("truth.bin");
    t.write(file);
    assertTrue(Files.exists(file));
    assertFalse("the temporary file should be renamed",
      Files.exists(file.resolveSibling("truth.bin.tmp")));
    assertTruthEquals(t, GroundTruth.read(file));
  }

  @Test
  public void testRoundTripOfComputedTruth() throws IOException {
    Corpus corpus = TruthTestSupport.synthetic(2500, 8, Metric.INNER_PRODUCT);
    List<PanelQuery> panel = corpus.panel(4);
    List<float[]> vectors = new ArrayList<>();
    List<List<QueryCondition>> conditions = new ArrayList<>();
    for (PanelQuery q : panel) {
      vectors.add(q.vector());
      List<QueryCondition> cs = new ArrayList<>();
      cs.add(QueryCondition.builder(QueryShape.UNFILTERED).build());
      cs.add(QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(q.tenant()).build());
      cs.add(QueryCondition.builder(QueryShape.COVERED_FILTER)
        .range(QueryCondition.Attribute.CATEGORY, 0, 1).tenant(q.tenant()).build());
      conditions.add(cs);
    }
    List<Map<String, NeighborList>> lists = new BruteForceKnn(Metric.INNER_PRODUCT, 2)
      .compute(vectors, conditions, RowSource.prefix(corpus, corpus.rows()), 50, null);
    GroundTruth t = new GroundTruth(corpus.fingerprint(), corpus.panelDigest(4), corpus.rows(),
      Metric.INNER_PRODUCT.name(), 50, lists);
    Path file = folder.getRoot().toPath().resolve("computed.gt");
    t.write(file);
    GroundTruth read = GroundTruth.read(file);
    assertTruthEquals(t, read);
    assertTrue(read.matches(corpus.fingerprint(), corpus.panelDigest(4), corpus.rows(),
      Metric.INNER_PRODUCT.name(), 50));
  }

  @Test
  public void testWriteCreatesDirectoriesAndReplacesFiles() throws IOException {
    Path file = folder.getRoot().toPath().resolve("a").resolve("b").resolve("truth.gt");
    sample().write(file);
    assertTruthEquals(sample(), GroundTruth.read(file));
    List<Map<String, NeighborList>> other = new ArrayList<>();
    Map<String, NeighborList> m = new LinkedHashMap<>();
    m.put("unfiltered", list(true, new long[] { 1 }, new double[] { 2.0 }));
    other.add(m);
    GroundTruth replacement = new GroundTruth("fp-2", "panel-2", 10, "L2", 11, other);
    replacement.write(file);
    assertTruthEquals(replacement, GroundTruth.read(file));
  }

  @Test
  public void testReadRejectsForeignFiles() throws IOException {
    Path notGzip = folder.newFile("plain.gt").toPath();
    Files.write(notGzip, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
    expectIOException(notGzip);

    Path wrongMagic = folder.newFile("magic.gt").toPath();
    writeGzipInts(wrongMagic, 0x12345678, 1);
    expectIOException(wrongMagic);

    Path wrongVersion = folder.newFile("version.gt").toPath();
    writeGzipInts(wrongVersion, 0x56484754, 99);
    expectIOException(wrongVersion);

    // A truncated file fails rather than returning partial lists.
    Path whole = folder.getRoot().toPath().resolve("whole.gt");
    sample().write(whole);
    byte[] bytes = Files.readAllBytes(whole);
    Path truncated = folder.getRoot().toPath().resolve("truncated.gt");
    Files.write(truncated, Arrays.copyOf(bytes, bytes.length / 2));
    expectIOException(truncated);
  }

  private static void writeGzipInts(Path file, int... values) throws IOException {
    try (OutputStream os = Files.newOutputStream(file);
      DataOutputStream out = new DataOutputStream(new GZIPOutputStream(os))) {
      for (int v : values) {
        out.writeInt(v);
      }
    }
  }

  private static void expectIOException(Path file) {
    try {
      GroundTruth.read(file);
      fail("read " + file);
    } catch (IOException e) {
      // expected
    }
  }

  @Test
  public void testMatches() {
    GroundTruth t = sample();
    assertTrue(t.matches("fp-0123", "panel-abcd", 1_000_000L, "COSINE", 201));
    // Longer lists than a run needs serve it; shorter ones do not.
    assertTrue(t.matches("fp-0123", "panel-abcd", 1_000_000L, "COSINE", 101));
    assertFalse(t.matches("fp-0123", "panel-abcd", 1_000_000L, "COSINE", 202));
    assertFalse(t.matches("fp-0124", "panel-abcd", 1_000_000L, "COSINE", 201));
    assertFalse(t.matches("fp-0123", "panel-abce", 1_000_000L, "COSINE", 201));
    assertFalse(t.matches("fp-0123", "panel-abcd", 999_999L, "COSINE", 201));
    assertFalse(t.matches("fp-0123", "panel-abcd", 1_000_000L, "L2", 201));
  }

  @Test
  public void testListsAreCopiedAndUnmodifiable() {
    List<Map<String, NeighborList>> lists = new ArrayList<>();
    Map<String, NeighborList> m = new LinkedHashMap<>();
    m.put("unfiltered", list(true, new long[] { 1 }, new double[] { 2.0 }));
    lists.add(m);
    GroundTruth t = new GroundTruth("fp", "pd", 1, "L2", 1, lists);
    m.put("later", NeighborList.empty());
    lists.add(new LinkedHashMap<>());
    assertEquals(1, t.queries());
    assertNull(t.get(0, "later"));
    try {
      t.forQuery(0).put("x", NeighborList.empty());
      fail("the lists of a query should be unmodifiable");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }
}
