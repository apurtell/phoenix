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
package org.apache.phoenix.vectorharness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.observe.JmxReader;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuildIndexPhaseTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static final String A = "VH.A_VIDX";
  private static final String B = "VH.B_VIDX";

  /** A reader whose indexes have uncovered regions until a number of reads. */
  private static final class CoveringReader implements JmxReader {
    final AtomicInteger reads = new AtomicInteger();
    final int coveredAfter;
    final boolean reportIndexes;

    CoveringReader(int coveredAfter, boolean reportIndexes) {
      this.coveredAfter = coveredAfter;
      this.reportIndexes = reportIndexes;
    }

    @Override
    public String mode() {
      return "stub";
    }

    @Override
    public Map<String, Map<String, Number>> read(String objectName) {
      int n = reads.incrementAndGet();
      Map<String, Number> attrs = new HashMap<>();
      attrs.put("vectorHnswRegionsUncovered", n > coveredAfter ? 0 : 3);
      if (reportIndexes) {
        attrs.put("vectorHnswRegionsUncovered." + A, n > coveredAfter ? 0 : 1);
        attrs.put("vectorHnswRegionsUncovered." + B, n > coveredAfter ? 0 : 2);
      }
      return Collections.singletonMap("rs1", attrs);
    }

    @Override
    public void close() {
    }
  }

  private static VectorIndexMetricsObserver observer(JmxReader reader, List<String> indexes) {
    return new VectorIndexMetricsObserver(reader, indexes, null, null);
  }

  @Test
  public void testWaitsUntilEveryRegionIsCovered() throws Exception {
    List<String> indexes = Arrays.asList(A, B);
    CoveringReader reader = new CoveringReader(3, true);
    assertTrue(BuildIndexPhase.awaitCoverage(observer(reader, indexes), indexes, 60_000));
    assertEquals(4, reader.reads.get());
  }

  @Test
  public void testTimesOutWhileRegionsStayUncovered() throws Exception {
    List<String> indexes = Arrays.asList(A, B);
    CoveringReader reader = new CoveringReader(Integer.MAX_VALUE, true);
    assertFalse(BuildIndexPhase.awaitCoverage(observer(reader, indexes), indexes, 300));
    assertTrue(reader.reads.get() >= 2);
  }

  @Test
  public void testDoesNotWaitForIndexesThatTheMetricsDoNotReport() throws Exception {
    List<String> indexes = Arrays.asList(A, B);
    // Server values alone do not hold the build of one index.
    CoveringReader reader = new CoveringReader(Integer.MAX_VALUE, false);
    assertTrue(BuildIndexPhase.awaitCoverage(observer(reader, indexes), indexes, 60_000));
    assertEquals(1, reader.reads.get());
  }

  /** Writes a load state and returns the error of reading it, or null if it is accepted. */
  private String readLoadState(Boolean publishedAgrees, Long completed) throws Exception {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "100");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    p.setProperty("run.output.dir", tmp.getRoot().getPath());
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      Manifest load = new Manifest();
      load.put("load", "fingerprint", ctx.corpus().fingerprint());
      load.put("load", "rows_loaded", 100L);
      if (publishedAgrees != null) {
        load.put("truth", "published_agrees", publishedAgrees);
      }
      if (completed != null) {
        load.put("load", "completed_ms", completed);
      }
      Path file = ctx.store().loadState(ctx.tables().suffix());
      Files.createDirectories(file.getParent());
      load.write(file);
      try {
        new BuildIndexPhase(ctx, new PrintStream(new java.io.ByteArrayOutputStream()))
          .readLoadState();
        return null;
      } catch (IllegalStateException e) {
        return e.getMessage();
      }
    }
  }

  @Test
  public void testRefusesTheStateOfAFailedLoad() throws Exception {
    assertEquals(null, readLoadState(true, 1000L));
    assertEquals(null, readLoadState(null, 1000L));
    String crashed = readLoadState(null, null);
    assertTrue(crashed, crashed.contains("did not complete"));
    // The state of a load whose truth disagreed with the published truth.
    String mismatch = readLoadState(false, null);
    assertTrue(mismatch, mismatch.contains("did not complete"));
    String disagrees = readLoadState(false, 1000L);
    if (disagrees == null) {
      fail("A disagreeing published truth was accepted");
    }
    assertTrue(disagrees, disagrees.contains("published ground truth"));
  }
}
