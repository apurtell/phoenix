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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.junit.Test;

public class RowSourceTest {

  private static List<Long> scan(RowSource source, long from, long to) {
    List<Long> out = new ArrayList<>();
    source.scan(from, to, (ordinal, vector, attributes) -> out.add(ordinal));
    return out;
  }

  @Test
  public void testPrefixVisitsBaseRowsInOrder() {
    Corpus corpus = TruthTestSupport.synthetic(300, 4, Metric.L2);
    RowSource source = RowSource.prefix(corpus, 100);
    assertEquals(100, source.ordinalLimit());
    List<Long> all = scan(source, 0, source.ordinalLimit());
    assertEquals(100, all.size());
    for (int i = 0; i < all.size(); i++) {
      assertEquals(i, all.get(i).longValue());
    }
    assertEquals(10, scan(source, 40, 50).size());
    assertEquals(40L, scan(source, 40, 50).get(0).longValue());
    // Ranges are clamped to [0, limit).
    assertEquals(5, scan(source, -5, 5).size());
    assertEquals(10, scan(source, 90, 1000).size());
    assertEquals(0, scan(source, 100, 200).size());
    assertEquals(0, scan(source, 60, 60).size());
  }

  @Test
  public void testPrefixIsClampedToTheCorpus() {
    Corpus corpus = TruthTestSupport.synthetic(300, 4, Metric.L2);
    assertEquals(300, RowSource.prefix(corpus, 10_000).ordinalLimit());
    assertEquals(300, scan(RowSource.prefix(corpus, 10_000), 0, 10_000).size());
    assertEquals(0, RowSource.prefix(corpus, 0).ordinalLimit());
  }

  @Test
  public void testPrefixVisitsTheCorpusRows() {
    Corpus corpus = TruthTestSupport.synthetic(300, 4, Metric.COSINE);
    RowSource.prefix(corpus, 300).scan(0, 300, (ordinal, vector, attributes) -> {
      assertArrayEquals(corpus.vector(ordinal), vector, 0f);
      assertEquals(corpus.attributes(ordinal), attributes);
      assertEquals(ordinal, attributes.ordinal());
    });
  }
}
