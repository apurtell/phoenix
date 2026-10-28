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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A TEXMEX corpus such as SIFT1M or GIST1M: a directory holding {@code <name>_base.fvecs} (or
 * {@code .bvecs}), {@code <name>_query.fvecs}, optionally {@code <name>_groundtruth.ivecs}, and
 * optionally {@code <name>_learn.fvecs}, which serves as the held-out partition of mutations. The
 * corpus is the first {@code rows} base vectors. The published ground truth describes the full base
 * set, so it is offered only when every base vector is loaded. TEXMEX corpora are Euclidean.
 */
public final class TexmexCorpus extends PublishedCorpus {

  private static final Logger LOG = LoggerFactory.getLogger(TexmexCorpus.class);

  private final Path dir;
  private final String name;
  private final VecsFile base;
  private final VecsFile query;
  private final VecsFile groundTruth;
  private final VecsFile learn;

  private TexmexCorpus(RandomStreams streams, AttributeModel attributes, Path dir, String name,
    VecsFile base, VecsFile query, VecsFile groundTruth, VecsFile learn, long rows) {
    super(streams, attributes, rows, Metric.L2);
    this.dir = dir;
    this.name = name;
    this.base = base;
    this.query = query;
    this.groundTruth = groundTruth;
    this.learn = learn;
  }

  /**
   * Opens a TEXMEX corpus directory.
   * @param name the file prefix, or null to detect the single {@code *_base} file
   * @param rows the number of base rows to use; 0 or more than the base count uses all of them
   */
  public static TexmexCorpus open(Path dir, String name, long rows, RandomStreams streams,
    AttributeModel attributes) throws IOException {
    if (!Files.isDirectory(dir)) {
      throw new IOException("TEXMEX corpus path is not a directory: " + dir);
    }
    String prefix = name != null ? name : detectName(dir);
    VecsFile base = new VecsFile(find(dir, prefix + "_base", true));
    VecsFile query = null;
    VecsFile gt = null;
    VecsFile learn = null;
    try {
      query = new VecsFile(find(dir, prefix + "_query", true));
      if (query.dim() != base.dim()) {
        throw new IOException(
          "Query dimension " + query.dim() + " differs from base dimension " + base.dim());
      }
      Path gtPath = dir.resolve(prefix + "_groundtruth.ivecs");
      gt = Files.exists(gtPath) ? new VecsFile(gtPath) : null;
      if (gt != null && gt.count() != query.count()) {
        LOG.warn("Ground truth of {} has {} rows for {} queries; ignoring it", prefix, gt.count(),
          query.count());
        gt.close();
        gt = null;
      }
      // An unreadable learn file fails the open: without it mutations would silently draw other
      // held-out vectors.
      Path learnPath = find(dir, prefix + "_learn", false);
      learn = learnPath != null ? new VecsFile(learnPath) : null;
      long n = rows <= 0 || rows > base.count() ? base.count() : rows;
      return new TexmexCorpus(streams, attributes, dir, prefix, base, query, gt, learn, n);
    } catch (IOException | RuntimeException e) {
      for (VecsFile f : new VecsFile[] { learn, gt, query, base }) {
        if (f != null) {
          try {
            f.close();
          } catch (IOException suppressed) {
            e.addSuppressed(suppressed);
          }
        }
      }
      throw e;
    }
  }

  private static String detectName(Path dir) throws IOException {
    String found = null;
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*_base.{fvecs,bvecs}")) {
      for (Path p : ds) {
        String f = p.getFileName().toString();
        String prefix = f.substring(0, f.lastIndexOf("_base."));
        if (found != null && !found.equals(prefix)) {
          throw new IOException(
            "Several TEXMEX corpora in " + dir + "; set corpus.name to one of them");
        }
        found = prefix;
      }
    }
    if (found == null) {
      throw new IOException("No *_base.fvecs or *_base.bvecs file in " + dir);
    }
    return found;
  }

  private static Path find(Path dir, String stem, boolean required) throws IOException {
    for (String ext : new String[] { ".fvecs", ".bvecs" }) {
      Path p = dir.resolve(stem + ext);
      if (Files.exists(p)) {
        return p;
      }
    }
    if (required) {
      throw new IOException("Missing " + stem + ".fvecs or " + stem + ".bvecs in " + dir);
    }
    return null;
  }

  @Override
  public String description() {
    return String.format(Locale.ROOT, "texmex:%s rows=%d dim=%d queries=%d", name, rows, dim(),
      query.count());
  }

  @Override
  public String sourceName() {
    return "texmex:" + name;
  }

  @Override
  public int dim() {
    return base.dim();
  }

  /** Returns true if the base vectors are bytes, whose integer distances tie often. */
  public boolean quantized() {
    return base.format() == VecsFile.Format.BVECS;
  }

  @Override
  public float[] vector(long ordinal) {
    if (ordinal < 0 || ordinal >= rows) {
      throw new IndexOutOfBoundsException("Row ordinal " + ordinal + " outside [0, " + rows + ")");
    }
    return base.floats(ordinal);
  }

  @Override
  protected int queryCount() {
    return (int) Math.min(Integer.MAX_VALUE, query.count());
  }

  @Override
  protected float[] queryVector(int q) {
    return query.floats(q);
  }

  @Override
  protected long heldOutCount() {
    if (learn != null) {
      return learn.count();
    }
    return base.count() - rows;
  }

  @Override
  protected float[] heldOutVector(long i) {
    if (learn != null) {
      return learn.floats(i);
    }
    return base.floats(rows + i);
  }

  @Override
  public int[][] publishedNeighbors() {
    if (groundTruth == null) {
      return null;
    }
    if (rows < base.count()) {
      LOG.info("Published ground truth of {} covers {} base rows but {} are loaded; it is not used",
        name, base.count(), rows);
      return null;
    }
    int n = (int) groundTruth.count();
    int[][] out = new int[n][];
    for (int q = 0; q < n; q++) {
      out[q] = groundTruth.ints(q);
    }
    return out;
  }

  @Override
  public Map<String, String> parameters() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("source", sourceName());
    m.put("rows", Long.toString(rows));
    m.put("dim", Integer.toString(dim()));
    m.put("base.count", Long.toString(base.count()));
    m.put("base.format", base.format().name());
    // The learn partition is what inserts and moves write, so it is part of the dataset.
    m.put("learn.count", learn == null ? "0" : Long.toString(learn.count()));
    if (learn != null) {
      m.put("learn.format", learn.format().name());
    }
    m.put("queries", Long.toString(query.count()));
    m.put("seed", Long.toString(streams.masterSeed()));
    for (Map.Entry<String, String> e : attributes.parameters().entrySet()) {
      m.put("attr." + e.getKey(), e.getValue());
    }
    return m;
  }

  @Override
  protected void digestFiles(MessageDigest md) {
    try {
      long recordBytes = 4L + (long) base.dim() * base.format().elementBytes();
      Digests.updateFile(md, base.path(), rows * recordBytes);
      Digests.updateFile(md, query.path(), -1);
      if (groundTruth != null) {
        Digests.updateFile(md, groundTruth.path(), -1);
      }
      // Mutations may draw any learn row, so all of it is digested.
      if (learn != null) {
        Digests.updateFile(md, learn.path(), -1);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    for (VecsFile f : new VecsFile[] { base, query, groundTruth, learn }) {
      if (f != null) {
        try {
          f.close();
        } catch (IOException e) {
          LOG.debug("Closing {}", f.path(), e);
        }
      }
    }
  }

  @Override
  public String toString() {
    return description() + " at " + dir;
  }
}
