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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.phoenix.vectorharness.config.HarnessConfig;

/**
 * The layout of the harness output directory, which holds state between the phases and the results
 * of runs:
 *
 * <pre>
 * corpus/&lt;fingerprint&gt;/truth-*.gt     cached ground truth
 * tables/&lt;suffix&gt;/load.json             what the load phase loaded
 * tables/&lt;suffix&gt;/build.json            what the build-index phase built
 * runs/&lt;run-id&gt;/manifest.json           the run manifest
 * runs/&lt;run-id&gt;/*.csv                   time series and measurements
 * runs/&lt;run-id&gt;/summary.txt             the console summary
 * </pre>
 */
public final class RunStore {

  public static final String MANIFEST = "manifest.json";
  public static final String LOAD_STATE = "load.json";
  public static final String BUILD_STATE = "build.json";

  private static final DateTimeFormatter RUN_TIME =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final Path root;

  public RunStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public static RunStore of(HarnessConfig config) {
    return new RunStore(config.outputDir());
  }

  public Path root() {
    return root;
  }

  public Path corpusDir(String fingerprint) {
    return root.resolve("corpus")
      .resolve(fingerprint.substring(0, Math.min(16, fingerprint.length())));
  }

  /** Returns the cache file of ground truth computed over a known set of rows. */
  public Path truthFile(String fingerprint, String panelDigest, long rows, String metric,
    String conditionsDigest) {
    String name = String.format(Locale.ROOT, "truth-%s-%d-%s-%s.gt",
      panelDigest.substring(0, Math.min(12, panelDigest.length())), rows,
      metric.toLowerCase(Locale.ROOT),
      conditionsDigest.substring(0, Math.min(12, conditionsDigest.length())));
    return corpusDir(fingerprint).resolve(name);
  }

  public Path tableDir(String suffix) {
    return root.resolve("tables").resolve(suffix);
  }

  public Path loadState(String suffix) {
    return tableDir(suffix).resolve(LOAD_STATE);
  }

  public Path buildState(String suffix) {
    return tableDir(suffix).resolve(BUILD_STATE);
  }

  public Path runDir(String runId) {
    return root.resolve("runs").resolve(runId);
  }

  /** Creates a run identifier from the variant, the profile, and the time. */
  public static String newRunId(String variant, String profile, Instant now) {
    return (variant + "-" + profile + "-" + RUN_TIME.format(now)).toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9._-]", "_");
  }

  /** Returns the run identifiers in the store, sorted. */
  public List<String> runs() throws IOException {
    Path dir = root.resolve("runs");
    if (!Files.isDirectory(dir)) {
      return Collections.emptyList();
    }
    List<String> out = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
      for (Path p : ds) {
        if (Files.exists(p.resolve(MANIFEST))) {
          out.add(p.getFileName().toString());
        }
      }
    }
    Collections.sort(out);
    return out;
  }

  /**
   * Returns the run that finished last: the one whose manifest records the latest end time, or, for
   * a manifest without one, was written last. Ties go to the identifier that sorts last. Returns
   * null if the store holds no run.
   */
  public String latestRun() throws IOException {
    String latest = null;
    long latestMillis = Long.MIN_VALUE;
    for (String id : runs()) {
      long t = finishedMillis(runDir(id).resolve(MANIFEST));
      if (latest == null || t >= latestMillis) {
        latest = id;
        latestMillis = t;
      }
    }
    return latest;
  }

  /** Returns the end time of the run of a manifest, else the time the manifest was written. */
  static long finishedMillis(Path manifest) throws IOException {
    try {
      Object end = Json.path(Json.readMap(manifest), "run.end_ms");
      if (end instanceof Number) {
        return ((Number) end).longValue();
      }
    } catch (IOException | RuntimeException e) {
      // An unreadable manifest falls back to its modification time.
    }
    return Files.getLastModifiedTime(manifest).toMillis();
  }

  /** Resolves a run identifier, a path to a run directory, or "latest". */
  public Path resolveRun(String idOrPath) throws IOException {
    if ("latest".equalsIgnoreCase(idOrPath)) {
      String latest = latestRun();
      if (latest == null) {
        throw new IOException("No runs in " + root);
      }
      return runDir(latest);
    }
    Path p = root.getFileSystem().getPath(idOrPath);
    if (Files.exists(p.resolve(MANIFEST))) {
      return p;
    }
    Path r = runDir(idOrPath);
    if (Files.exists(r.resolve(MANIFEST))) {
      return r;
    }
    throw new IOException("No run " + idOrPath + " in " + root);
  }
}
