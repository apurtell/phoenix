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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TruthManagerTest {

  private static final long ROWS = 300;

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static HarnessConfig config(Path out, String kValues, String shapes) {
    Properties p = new Properties();
    p.setProperty("corpus.rows", Long.toString(ROWS));
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "3");
    p.setProperty("recall.panel.size", "6");
    p.setProperty("query.k.values", kValues);
    p.setProperty("query.shapes", shapes);
    p.setProperty("run.output.dir", out.toString());
    return HarnessConfig.of("t", p);
  }

  private static long[] ordinals(NeighborList l) {
    long[] o = new long[l.size()];
    for (int i = 0; i < o.length; i++) {
      o[i] = l.ordinal(i);
    }
    return o;
  }

  /** Reads the truth of a profile, from the cache its first profile wrote, and checks it. */
  private void assertCachedTruthServes(String firstK, String firstShapes, String k, String shapes)
    throws Exception {
    Path out = tmp.newFolder().toPath();
    Path file;
    String digest;
    try (HarnessContext ctx = HarnessContext.open(config(out, firstK, firstShapes))) {
      TruthManager tm = new TruthManager(ctx);
      tm.truth(ROWS);
      digest = tm.conditionsDigest();
      file = ctx.store().truthFile(ctx.corpus().fingerprint(),
        ctx.corpus().panelDigest(tm.panel().size()), ROWS, ctx.metric().name(), digest);
      assertTrue(Files.exists(file));
    }
    FileTime written = Files.getLastModifiedTime(file);
    Path fresh = tmp.newFolder().toPath();
    try (HarnessContext ctx = HarnessContext.open(config(out, k, shapes));
      HarnessContext exact = HarnessContext.open(config(fresh, k, shapes))) {
      TruthManager tm = new TruthManager(ctx);
      assertEquals("the profiles share the cached file", digest, tm.conditionsDigest());
      GroundTruth cached = tm.truth(ROWS);
      assertEquals("the cached file was reused", written, Files.getLastModifiedTime(file));
      GroundTruth computed = new TruthManager(exact).truth(ROWS);
      List<List<QueryCondition>> conditions = tm.conditions(cached);
      int bounded = 0;
      for (int i = 0; i < conditions.size(); i++) {
        for (QueryCondition c : conditions.get(i)) {
          NeighborList l = cached.get(i, c.key());
          assertNotNull(c.key() + " of query " + i, l);
          assertArrayEquals(c.key(), ordinals(computed.get(i, c.key())), ordinals(l));
          if (c.hasRadius()) {
            bounded++;
          }
        }
      }
      assertTrue(bounded > 0);
    }
  }

  @Test
  public void testCachedTruthServesOtherKValues() throws Exception {
    assertCachedTruthServes("10,100", "all", "20,100", "all");
    assertCachedTruthServes("10,100", "all", "1,10,50,100", "all");
  }

  @Test
  public void testCachedTruthServesDistanceBoundsItWasNotComputedFor() throws Exception {
    String relational = "unfiltered,covered_filter,uncovered_filter";
    assertCachedTruthServes("10,100", relational, "10,100",
      relational + "," + QueryShape.DISTANCE_BOUND.id());
  }
}
