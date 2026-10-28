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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.phoenix.vectorharness.result.Json;
import org.apache.phoenix.vectorharness.result.RunStore;

/**
 * Helpers of the harness integration tests: small profiles for the shared minicluster, and an
 * in-process invocation of the command line that captures its output.
 */
final class HarnessItSupport {

  private HarnessItSupport() {
  }

  /** The outcome of one command line invocation. */
  static final class Invocation {
    final int status;
    final String out;
    final String err;

    Invocation(int status, String out, String err) {
      this.status = status;
      this.out = out;
      this.err = err;
    }

    @Override
    public String toString() {
      return "status " + status + "\n--- stdout\n" + out + "\n--- stderr\n" + err;
    }
  }

  /**
   * Returns a small synthetic profile: about a thousand rows of dimension 16 in eight clusters, a
   * panel of 24 queries, and steady-state windows of a few seconds, with metrics read from the
   * in-process registry of the minicluster.
   */
  static Properties profile(String url, Path outputDir, String variant, String suffix) {
    Properties p = new Properties();
    p.setProperty("jdbc.url", url);
    p.setProperty("run.output.dir", outputDir.toString());
    p.setProperty("run.variant", variant);
    p.setProperty("table.suffix", suffix);
    p.setProperty("corpus.source", "synthetic");
    p.setProperty("corpus.seed", "7");
    p.setProperty("corpus.rows", "1200");
    p.setProperty("corpus.dim", "16");
    p.setProperty("corpus.clusters", "8");
    p.setProperty("corpus.intrinsic_dim", "6");
    p.setProperty("corpus.filter.categories", "20");
    p.setProperty("corpus.filter.sources", "20");
    p.setProperty("corpus.body.bytes", "16");
    p.setProperty("index.metric", "L2");
    p.setProperty("table.regions", "2");
    p.setProperty("table.layouts", "corpus");
    p.setProperty("index.build.timeout", "5m");
    p.setProperty("ivf.lists", "8");
    p.setProperty("ivf.sample_size", "400");
    p.setProperty("hnsw.m", "8");
    p.setProperty("hnsw.ef_construction", "32");
    p.setProperty("ingest.threads", "2");
    p.setProperty("ingest.batch.size", "200");
    p.setProperty("query.k.values", "10");
    p.setProperty("query.ivf.probe.values", "1,2,all");
    p.setProperty("query.ivf.max_probe_limit", "64");
    p.setProperty("query.hnsw.ef.values", "16,64,256");
    p.setProperty("query.filter.selectivity", "0.25");
    p.setProperty("query.shapes",
      "unfiltered,covered_filter,uncovered_filter,pk_range," + "distance_bound");
    p.setProperty("query.threads", "2");
    p.setProperty("query.rate.per_sec", "10");
    p.setProperty("query.timeout", "60s");
    p.setProperty("mutation.rate.per_sec", "4");
    p.setProperty("mutation.threads", "1");
    p.setProperty("steady_state.warmup", "1s");
    p.setProperty("steady_state.duration", "5s");
    p.setProperty("recall.panel.size", "24");
    p.setProperty("recall.sample.queries", "12");
    p.setProperty("recall.sample.interval", "2s");
    p.setProperty("recall.bootstrap.resamples", "200");
    p.setProperty("freshness.rate.per_sec", "1");
    p.setProperty("observe.interval", "1s");
    p.setProperty("observe.jmx.mode", "registry");
    p.setProperty("phoenix.index.population.wait.time", "0");
    return p;
  }

  /** Writes a profile to a properties file and returns its path. */
  static Path write(Properties p, Path dir, String name) throws IOException {
    Files.createDirectories(dir);
    Path file = dir.resolve(name + ".properties");
    Map<String, String> sorted = new TreeMap<>();
    for (String k : p.stringPropertyNames()) {
      sorted.put(k, p.getProperty(k));
    }
    try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, String> e : sorted.entrySet()) {
        // Properties.store would escape the colons of the JDBC URL; write the pairs verbatim
        // with backslashes doubled, which Properties.load reads back unchanged.
        w.write(e.getKey() + "=" + e.getValue().replace("\\", "\\\\") + "\n");
      }
    }
    return file;
  }

  /** Runs the command line in process. */
  static Invocation harness(String... args) throws UnsupportedEncodingException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int status;
    try (PrintStream o = new PrintStream(out, true, "UTF-8");
      PrintStream e = new PrintStream(err, true, "UTF-8")) {
      status = VectorHarness.run(args, o, e);
    }
    return new Invocation(status, new String(out.toByteArray(), StandardCharsets.UTF_8),
      new String(err.toByteArray(), StandardCharsets.UTF_8));
  }

  /** Runs a phase of the harness with a profile, failing the test unless it succeeds. */
  static Invocation phase(String phase, Path profile, String... extra) throws Exception {
    List<String> args = new ArrayList<>(Arrays.asList(phase, "--config", profile.toString()));
    args.addAll(Arrays.asList(extra));
    Invocation r = harness(args.toArray(new String[0]));
    if (r.status != VectorHarness.OK) {
      throw new AssertionError(phase + " failed: " + r);
    }
    return r;
  }

  /** Reads the manifest of a run. */
  static Map<String, Object> manifest(Path outputDir, String runId) throws IOException {
    return Json.readMap(new RunStore(outputDir).runDir(runId).resolve(RunStore.MANIFEST));
  }

  static Object get(Map<String, Object> m, String dotted) {
    return Json.path(m, dotted);
  }

  static double number(Map<String, Object> m, String dotted) {
    Object v = Json.path(m, dotted);
    return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
  }
}
