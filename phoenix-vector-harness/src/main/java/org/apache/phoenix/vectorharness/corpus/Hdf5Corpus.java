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

import io.jhdf.HdfFile;
import io.jhdf.api.Attribute;
import io.jhdf.api.Dataset;
import io.jhdf.api.Node;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.Metric;

/**
 * An HDF5 corpus in the ANN-Benchmarks schema: datasets {@code train} (base vectors), {@code test}
 * (queries), optionally {@code neighbors} (published ground truth), and the root attribute
 * {@code distance} (euclidean, angular, or dot). The corpus is the first {@code rows} train
 * vectors. Base vectors are read in blocks, so the file need not fit in memory.
 */
public final class Hdf5Corpus extends PublishedCorpus {

  static final int BLOCK_ROWS = 4096;
  static final int CACHED_BLOCKS = 64;

  private final Path file;
  private final HdfFile hdf;
  private final Dataset train;
  private final long trainCount;
  private final int dim;
  private final float[][] queries;
  private final int[][] neighbors;
  private final String distanceName;
  private final Map<Long, float[][]> blocks =
    new LinkedHashMap<Long, float[][]>(CACHED_BLOCKS * 2, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, float[][]> eldest) {
        return size() > CACHED_BLOCKS;
      }
    };

  private Hdf5Corpus(RandomStreams streams, AttributeModel attributes, Path file, HdfFile hdf,
    Dataset train, long rows, Metric metric, String distanceName, float[][] queries,
    int[][] neighbors) {
    super(streams, attributes, rows, metric);
    this.file = file;
    this.hdf = hdf;
    this.train = train;
    this.trainCount = train.getDimensions()[0];
    this.dim = train.getDimensions()[1];
    this.distanceName = distanceName;
    this.queries = queries;
    this.neighbors = neighbors;
  }

  /** Opens an ANN-Benchmarks HDF5 file. */
  public static Hdf5Corpus open(Path file, long rows, RandomStreams streams,
    AttributeModel attributes) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IOException("HDF5 corpus path is not a file: " + file);
    }
    HdfFile hdf = new HdfFile(file);
    try {
      Dataset train = dataset(hdf, "train", true);
      Dataset test = dataset(hdf, "test", true);
      int[] td = train.getDimensions();
      int[] qd = test.getDimensions();
      if (td.length != 2 || qd.length != 2 || td[1] != qd[1]) {
        throw new IOException(
          "train and test must be two dimensional with equal widths in " + file);
      }
      String distance = "euclidean";
      Attribute attr = hdf.getAttribute("distance");
      if (attr != null) {
        Object v = attr.getData();
        distance = String.valueOf(v.getClass().isArray() ? Array.get(v, 0) : v);
      }
      Metric metric = metricOf(distance);
      float[][] queries = toFloats(test.getData());
      Dataset nb = dataset(hdf, "neighbors", false);
      int[][] neighbors = nb == null ? null : toInts(nb.getData());
      long n = rows <= 0 || rows > td[0] ? td[0] : rows;
      return new Hdf5Corpus(streams, attributes, file, hdf, train, n, metric, distance, queries,
        neighbors);
    } catch (IOException | RuntimeException e) {
      hdf.close();
      throw e;
    }
  }

  private static Dataset dataset(HdfFile hdf, String name, boolean required) throws IOException {
    Node node = hdf.getChildren().get(name);
    if (node instanceof Dataset) {
      return (Dataset) node;
    }
    if (required) {
      throw new IOException("Missing dataset " + name + " in " + hdf.getFile());
    }
    return null;
  }

  /** Maps the ANN-Benchmarks distance attribute to a metric. */
  static Metric metricOf(String distance) {
    String d = distance.trim().toLowerCase(Locale.ROOT);
    switch (d) {
      case "euclidean":
      case "l2":
        return Metric.L2;
      case "angular":
      case "cosine":
        return Metric.COSINE;
      case "dot":
      case "ip":
      case "inner_product":
        return Metric.INNER_PRODUCT;
      default:
        throw new IllegalArgumentException("Unsupported HDF5 corpus distance: " + distance);
    }
  }

  static float[][] toFloats(Object data) {
    if (data instanceof float[][]) {
      return (float[][]) data;
    }
    if (data instanceof double[][]) {
      double[][] d = (double[][]) data;
      float[][] out = new float[d.length][];
      for (int i = 0; i < d.length; i++) {
        out[i] = new float[d[i].length];
        for (int j = 0; j < d[i].length; j++) {
          out[i][j] = (float) d[i][j];
        }
      }
      return out;
    }
    throw new IllegalArgumentException("Expected a 2-D float dataset, got " + data.getClass());
  }

  static int[][] toInts(Object data) {
    if (data instanceof int[][]) {
      return (int[][]) data;
    }
    if (data instanceof long[][]) {
      long[][] d = (long[][]) data;
      int[][] out = new int[d.length][];
      for (int i = 0; i < d.length; i++) {
        out[i] = new int[d[i].length];
        for (int j = 0; j < d[i].length; j++) {
          out[i][j] = Math.toIntExact(d[i][j]);
        }
      }
      return out;
    }
    throw new IllegalArgumentException("Expected a 2-D integer dataset, got " + data.getClass());
  }

  @Override
  public String description() {
    return String.format(Locale.ROOT, "hdf5:%s rows=%d dim=%d queries=%d distance=%s", baseName(),
      rows, dim, queries.length, distanceName);
  }

  @Override
  public String sourceName() {
    return "hdf5:" + baseName();
  }

  private String baseName() {
    String f = file.getFileName().toString();
    int dot = f.lastIndexOf('.');
    return dot > 0 ? f.substring(0, dot) : f;
  }

  @Override
  public int dim() {
    return dim;
  }

  @Override
  public float[] vector(long ordinal) {
    if (ordinal < 0 || ordinal >= rows) {
      throw new IndexOutOfBoundsException("Row ordinal " + ordinal + " outside [0, " + rows + ")");
    }
    return trainRow(ordinal);
  }

  private float[] trainRow(long i) {
    long block = i / BLOCK_ROWS;
    float[][] rowsOfBlock;
    synchronized (blocks) {
      rowsOfBlock = blocks.get(block);
      if (rowsOfBlock == null) {
        long start = block * BLOCK_ROWS;
        int n = (int) Math.min(BLOCK_ROWS, trainCount - start);
        rowsOfBlock = toFloats(train.getData(new long[] { start, 0 }, new int[] { n, dim }));
        blocks.put(block, rowsOfBlock);
      }
    }
    return rowsOfBlock[(int) (i - block * BLOCK_ROWS)];
  }

  @Override
  protected int queryCount() {
    return queries.length;
  }

  @Override
  protected float[] queryVector(int q) {
    return queries[q];
  }

  @Override
  protected long heldOutCount() {
    return trainCount - rows;
  }

  @Override
  protected float[] heldOutVector(long i) {
    return trainRow(rows + i);
  }

  @Override
  public int[][] publishedNeighbors() {
    if (neighbors == null || rows < trainCount) {
      return null;
    }
    return neighbors;
  }

  @Override
  public Map<String, String> parameters() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("source", sourceName());
    m.put("rows", Long.toString(rows));
    m.put("dim", Integer.toString(dim));
    m.put("train.count", Long.toString(trainCount));
    m.put("queries", Integer.toString(queries.length));
    m.put("distance", distanceName);
    m.put("seed", Long.toString(streams.masterSeed()));
    for (Map.Entry<String, String> e : attributes.parameters().entrySet()) {
      m.put("attr." + e.getKey(), e.getValue());
    }
    return m;
  }

  @Override
  protected void digestFiles(MessageDigest md) {
    // HDF5 layout bytes are not canonical, so the digest covers the decoded values.
    for (long i = 0; i < rows; i++) {
      Digests.update(md, trainRow(i));
    }
    for (float[] q : queries) {
      Digests.update(md, q);
    }
    if (neighbors != null) {
      for (int[] row : neighbors) {
        for (int x : row) {
          Digests.update(md, x);
        }
      }
    }
  }

  @Override
  public void close() {
    try {
      hdf.close();
    } catch (RuntimeException e) {
      throw new UncheckedIOException(new IOException("Closing " + file, e));
    }
  }
}
