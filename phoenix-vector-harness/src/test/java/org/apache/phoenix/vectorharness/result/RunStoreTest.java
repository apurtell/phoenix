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
package org.apache.phoenix.vectorharness.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RunStoreTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static void run(RunStore store, String id, Long endMillis) throws IOException {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", id);
    if (endMillis != null) {
      run.put("end_ms", endMillis);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("run", run);
    Json.write(store.runDir(id).resolve(RunStore.MANIFEST), m);
  }

  @Test
  public void testLatestIsTheRunThatFinishedLast() throws IOException {
    RunStore store = new RunStore(tmp.getRoot().toPath());
    assertNull(store.latestRun());
    try {
      store.resolveRun("latest");
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("No runs"));
    }
    // Identifiers that sort in another order than the runs finished, as generated identifiers of
    // different variants do.
    run(store, "ivf-p-20260101T000000Z", 1_000L);
    run(store, "hnsw-p-20260102T000000Z", 3_000L);
    run(store, "exact-p-20260103T000000Z", 2_000L);
    Files.createDirectories(store.runDir("zz-no-manifest"));
    assertEquals(Arrays.asList("exact-p-20260103T000000Z", "hnsw-p-20260102T000000Z",
      "ivf-p-20260101T000000Z"), store.runs());
    assertEquals("hnsw-p-20260102T000000Z", store.latestRun());
    assertEquals(store.runDir("hnsw-p-20260102T000000Z"), store.resolveRun("latest"));
    assertEquals(store.runDir("hnsw-p-20260102T000000Z"), store.resolveRun("LATEST"));
    assertEquals(store.runDir("ivf-p-20260101T000000Z"),
      store.resolveRun("ivf-p-20260101T000000Z"));
  }

  @Test
  public void testLatestFallsBackToTheManifestTime() throws IOException {
    RunStore store = new RunStore(tmp.getRoot().toPath());
    run(store, "b", 5_000L);
    run(store, "a", null);
    Path a = store.runDir("a").resolve(RunStore.MANIFEST);
    Files.setLastModifiedTime(a, FileTime.fromMillis(9_000L));
    assertEquals("a", store.latestRun());
    Files.setLastModifiedTime(a, FileTime.fromMillis(4_000L));
    assertEquals("b", store.latestRun());
    // Equal times go to the identifier that sorts last.
    run(store, "c", 5_000L);
    assertEquals("c", store.latestRun());
  }
}
