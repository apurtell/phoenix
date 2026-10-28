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
import io.jhdf.api.dataset.ContiguousDataset;
import io.jhdf.object.datatype.DataType;
import io.jhdf.object.datatype.FloatingPoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Published vector corpus backed by ANN-Benchmarks HDF5 format, reading datasets {@code train}
 * (base vectors), {@code test} (queries), {@code neighbors} (ground truth), and the root
 * {@code distance} attribute. The corpus reads a contiguous train dataset without filters through
 * memory maps, so a random row read costs as little as a sequential read. Other layouts use a cache
 * of decoded row blocks.
 */
public final class Hdf5Corpus extends PublishedCorpus {

  private static final Logger LOG = LoggerFactory.getLogger(Hdf5Corpus.class);

  static final int BLOCK_ROWS = 4096;
  static final int CACHED_BLOCKS = 64;
  /** Largest size of one memory mapped region of the train dataset. */
  static final long MAX_MAP_BYTES = 1L << 30;

  private final Path file;
  private final HdfFile hdf;
  private final Dataset train;
  private final MappedRows mapped;
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
    Dataset train, MappedRows mapped, long rows, Metric metric, String distanceName,
    float[][] queries, int[][] neighbors) {
    super(streams, attributes, rows, metric);
    this.file = file;
    this.hdf = hdf;
    this.train = train;
    this.mapped = mapped;
    this.trainCount = train.getDimensions()[0];
    this.dim = train.getDimensions()[1];
    this.distanceName = distanceName;
    this.queries = queries;
    this.neighbors = neighbors;
  }

  /** Opens an ANN-Benchmarks formatted HDF5 corpus file. */
  public static Hdf5Corpus open(Path file, long rows, RandomStreams streams,
    AttributeModel attributes) throws IOException {
    return open(file, rows, 0, streams, attributes, true);
  }

  /**
   * Opens an ANN-Benchmarks formatted HDF5 corpus file and holds out a tail of the train rows.
   * @param holdout the count of train rows at the tail that the corpus does not load
   */
  public static Hdf5Corpus open(Path file, long rows, long holdout, RandomStreams streams,
    AttributeModel attributes) throws IOException {
    return open(file, rows, holdout, streams, attributes, true);
  }

  static Hdf5Corpus open(Path file, long rows, RandomStreams streams, AttributeModel attributes,
    boolean mapRows) throws IOException {
    return open(file, rows, 0, streams, attributes, mapRows);
  }

  /**
   * Opens an ANN-Benchmarks formatted HDF5 corpus file.
   * @param mapRows false to read the train rows in decoded blocks even when a memory map is
   *                possible
   */
  static Hdf5Corpus open(Path file, long rows, long holdout, RandomStreams streams,
    AttributeModel attributes, boolean mapRows) throws IOException {
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
      long n = loadedRows(td[0], rows, holdout, file);
      MappedRows mapped = mapRows ? MappedRows.map(file, hdf, train) : null;
      if (mapped != null && !mapped.agreesWith(train)) {
        LOG.warn("Memory mapped rows of {} differ from the HDF5 reader; using block reads", file);
        mapped = null;
      }
      LOG.info("Reading train rows of {} through {}", file,
        mapped != null ? "a memory map" : "decoded blocks");
      return new Hdf5Corpus(streams, attributes, file, hdf, train, mapped, n, metric, distance,
        queries, neighbors);
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

  /** Maps the ANN-Benchmarks distance attribute name to a {@link Metric}. */
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
    return String.format(Locale.ROOT, "hdf5:%s rows=%d held_out=%d dim=%d queries=%d distance=%s",
      baseName(), rows, heldOutCount(), dim, queries.length, distanceName);
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

  /** Returns true if train rows come from memory maps and not from decoded blocks. */
  boolean mapped() {
    return mapped != null;
  }

  private float[] trainRow(long i) {
    if (mapped != null) {
      return mapped.row(i);
    }
    return blockRow(i);
  }

  private float[] blockRow(long i) {
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
    // Digest decoded vector values to ensure canonical fingerprint independent of HDF5 layout.
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

  /**
   * Reads the train rows of a contiguous dataset without filters directly from memory maps of the
   * file. Such a dataset is one row-major array, so a row read is a copy at a known offset. Each
   * region holds whole rows. The reads use absolute offsets, so threads can share the regions.
   */
  static final class MappedRows {

    private final ByteBuffer[] regions;
    private final long rowsPerRegion;
    private final int rowBytes;
    private final int dim;
    private final long count;
    private final boolean doubles;

    private MappedRows(ByteBuffer[] regions, long rowsPerRegion, int rowBytes, int dim, long count,
      boolean doubles) {
      this.regions = regions;
      this.rowsPerRegion = rowsPerRegion;
      this.rowBytes = rowBytes;
      this.dim = dim;
      this.count = count;
      this.doubles = doubles;
    }

    /** Maps the train dataset, or returns null if its layout or type needs the HDF5 reader. */
    static MappedRows map(Path file, HdfFile hdf, Dataset train) throws IOException {
      return map(file, hdf, train, MAX_MAP_BYTES);
    }

    /** Maps the train dataset in regions of at most {@code maxRegionBytes} bytes. */
    static MappedRows map(Path file, HdfFile hdf, Dataset train, long maxRegionBytes)
      throws IOException {
      if (!(train instanceof ContiguousDataset) || !train.getFilters().isEmpty()) {
        return null;
      }
      DataType type = train.getDataType();
      if (!(type instanceof FloatingPoint) || (type.getSize() != 4 && type.getSize() != 8)) {
        return null;
      }
      int[] d = train.getDimensions();
      long count = d[0];
      int dim = d[1];
      long rowBytes = (long) dim * type.getSize();
      if (count == 0 || dim == 0 || rowBytes > maxRegionBytes) {
        return null;
      }
      // The HDF5 reader also adds the user block size to the data address.
      long start = hdf.getUserBlockSize() + ((ContiguousDataset) train).getDataAddress();
      try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
        if (start < 0 || start + count * rowBytes > ch.size()) {
          return null;
        }
        long perRegion = maxRegionBytes / rowBytes;
        int n = Math.toIntExact((count + perRegion - 1) / perRegion);
        ByteBuffer[] regions = new ByteBuffer[n];
        for (int r = 0; r < n; r++) {
          long first = r * perRegion;
          long bytes = Math.min(perRegion, count - first) * rowBytes;
          regions[r] = ch.map(FileChannel.MapMode.READ_ONLY, start + first * rowBytes, bytes)
            .order(((FloatingPoint) type).getByteOrder());
        }
        return new MappedRows(regions, perRegion, (int) rowBytes, dim, count, type.getSize() == 8);
      }
    }

    /** Returns the number of mapped regions. */
    int regionCount() {
      return regions.length;
    }

    /** Returns the train row at index {@code i}. */
    float[] row(long i) {
      ByteBuffer b = regions[(int) (i / rowsPerRegion)];
      int pos = (int) ((i % rowsPerRegion) * rowBytes);
      float[] out = new float[dim];
      if (doubles) {
        for (int j = 0; j < dim; j++) {
          out[j] = (float) b.getDouble(pos + 8 * j);
        }
      } else {
        for (int j = 0; j < dim; j++) {
          out[j] = b.getFloat(pos + 4 * j);
        }
      }
      return out;
    }

    /** Returns true if the first, middle, and last rows agree with the HDF5 reader. */
    boolean agreesWith(Dataset train) {
      for (long i : new long[] { 0, count / 2, count - 1 }) {
        float[] expected = toFloats(train.getData(new long[] { i, 0 }, new int[] { 1, dim }))[0];
        if (!Arrays.equals(expected, row(i))) {
          return false;
        }
      }
      return true;
    }
  }
}
