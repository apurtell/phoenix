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
package org.apache.phoenix.mapreduce.vector;

import io.github.jbellis.jvector.disk.ByteBufferReader;
import io.github.jbellis.jvector.disk.IndexWriter;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.disk.GraphIndexFormatFactory;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.phoenix.index.vector.KMeansTrainer;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Trains and distributes global Product Quantization (PQ) codebooks for HNSW index builds.
 * <p>
 * In distributed HNSW index construction, PQ codebooks must be trained globally across the full
 * dataset rather than per-region so that distance metrics remain comparable across regions during
 * query-time distributed merges.
 * </p>
 * <p>
 * This class coordinates:
 * <ol>
 * <li>Sampling representative vectors from the Phoenix base table via
 * {@link KMeansTrainer#sampleVectors}.</li>
 * <li>Training a JVector {@link ProductQuantization} codebook centered on a global centroid.</li>
 * <li>Serializing the trained codebook to HDFS / file storage.</li>
 * <li>Distributing the codebook to MapReduce tasks via Hadoop's DistributedCache and
 * configuration.</li>
 * </ol>
 * </p>
 */
public final class HnswPqCodebookTrainer {

  private static final Logger LOGGER = LoggerFactory.getLogger(HnswPqCodebookTrainer.class);

  public static final int DEFAULT_CLUSTER_COUNT = 256;

  private HnswPqCodebookTrainer() {
    // Utility class
  }

  /**
   * Samples vectors from the specified Phoenix data table and trains a global
   * {@link ProductQuantization} codebook.
   * @param conn          the Phoenix connection to execute sampling queries
   * @param dataTable     the base data table name
   * @param vectorColExpr the SQL expression selecting the vector column
   * @param dimension     the dimensionality of the vectors
   * @param pqSegments    the number of PQ sub-quantizer segments (M)
   * @param sampleSize    the maximum number of vector rows to sample
   * @return the trained {@link ProductQuantization} codebook
   * @throws SQLException if an error occurs while sampling from Phoenix
   */
  public static ProductQuantization trainCodebook(PhoenixConnection conn, String dataTable,
    String vectorColExpr, int dimension, int pqSegments, int sampleSize) throws SQLException {
    Preconditions.checkNotNull(conn, "conn must not be null");
    Preconditions.checkNotNull(dataTable, "dataTable must not be null");
    Preconditions.checkNotNull(vectorColExpr, "vectorColExpr must not be null");
    Preconditions.checkArgument(sampleSize > 0, "sampleSize must be > 0: %s", sampleSize);

    LOGGER.info("Sampling up to {} vectors from table {} (col: {}) for PQ codebook training",
      sampleSize, dataTable, vectorColExpr);
    List<float[]> samples = KMeansTrainer.sampleVectors(conn, dataTable, vectorColExpr, sampleSize);
    if (samples == null || samples.isEmpty()) {
      throw new IllegalStateException(
        "No vector samples retrieved from table " + dataTable + " for column " + vectorColExpr);
    }
    LOGGER.info("Sampled {} vectors from table {}; training PQ codebook (dim={}, segments={})",
      samples.size(), dataTable, dimension, pqSegments);
    return trainCodebook(samples, dimension, pqSegments);
  }

  /**
   * Trains a {@link ProductQuantization} codebook from a list of float vector arrays.
   * @param samples    the sampled vector data
   * @param dimension  the expected vector dimension
   * @param pqSegments the number of PQ sub-quantizers (M)
   * @return the trained {@link ProductQuantization} codebook
   */
  public static ProductQuantization trainCodebook(List<float[]> samples, int dimension,
    int pqSegments) {
    Preconditions.checkNotNull(samples, "samples must not be null");
    Preconditions.checkArgument(!samples.isEmpty(), "samples must not be empty");
    Preconditions.checkArgument(dimension > 0, "dimension must be > 0: %s", dimension);
    Preconditions.checkArgument(pqSegments > 0, "pqSegments must be > 0: %s", pqSegments);
    Preconditions.checkArgument(dimension % pqSegments == 0,
      "dimension (%s) must be divisible by pqSegments (%s)", dimension, pqSegments);

    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    List<VectorFloat<?>> vectorList = new ArrayList<>(samples.size());
    for (float[] sample : samples) {
      Preconditions.checkNotNull(sample, "sample vector must not be null");
      Preconditions.checkArgument(sample.length == dimension,
        "Sample vector dimension %s does not match expected dimension %s", sample.length,
        dimension);
      vectorList.add(vts.createFloatVector(sample));
    }

    ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vectorList, dimension);
    return trainCodebook(ravv, pqSegments);
  }

  /**
   * Trains a {@link ProductQuantization} codebook from a {@link RandomAccessVectorValues} source.
   * @param ravv       the vector values to train on
   * @param pqSegments the number of PQ sub-quantizers (M)
   * @return the trained {@link ProductQuantization} codebook
   */
  public static ProductQuantization trainCodebook(RandomAccessVectorValues ravv, int pqSegments) {
    Preconditions.checkNotNull(ravv, "ravv must not be null");
    Preconditions.checkArgument(pqSegments > 0, "pqSegments must be > 0: %s", pqSegments);
    Preconditions.checkArgument(ravv.dimension() % pqSegments == 0,
      "dimension (%s) must be divisible by pqSegments (%s)", ravv.dimension(), pqSegments);

    return ProductQuantization.compute(ravv, pqSegments, DEFAULT_CLUSTER_COUNT, true);
  }

  /**
   * Serializes a {@link ProductQuantization} codebook to HDFS / file storage via
   * {@link DataOutputStream}.
   * @param pq         the codebook to serialize
   * @param outputPath the destination path
   * @param conf       the Hadoop configuration
   * @throws IOException if serialization fails
   */
  public static void serializeCodebook(ProductQuantization pq, Path outputPath, Configuration conf)
    throws IOException {
    Preconditions.checkNotNull(pq, "pq must not be null");
    Preconditions.checkNotNull(outputPath, "outputPath must not be null");
    Preconditions.checkNotNull(conf, "conf must not be null");

    FileSystem fs = outputPath.getFileSystem(conf);
    try (FSDataOutputStream fsOut = fs.create(outputPath, true)) {
      serializeCodebook(pq, (DataOutputStream) fsOut);
    }
  }

  /**
   * Serializes a {@link ProductQuantization} codebook to an output stream.
   * @param pq  the codebook to serialize
   * @param out the output stream
   * @throws IOException if serialization fails
   */
  public static void serializeCodebook(ProductQuantization pq, DataOutputStream out)
    throws IOException {
    Preconditions.checkNotNull(pq, "pq must not be null");
    Preconditions.checkNotNull(out, "out must not be null");

    OutputStreamIndexWriter writer = new OutputStreamIndexWriter(out);
    pq.write(writer, GraphIndexFormatFactory.getCurrentVersion());
  }

  /**
   * Loads a {@link ProductQuantization} codebook from HDFS / file storage via
   * {@link DataInputStream}.
   * @param codebookPath the path to the serialized codebook
   * @param conf         the Hadoop configuration
   * @return the deserialized {@link ProductQuantization} codebook
   * @throws IOException if reading fails
   */
  public static ProductQuantization loadCodebook(Path codebookPath, Configuration conf)
    throws IOException {
    Preconditions.checkNotNull(codebookPath, "codebookPath must not be null");
    Preconditions.checkNotNull(conf, "conf must not be null");

    FileSystem fs = codebookPath.getFileSystem(conf);
    try (FSDataInputStream fsIn = fs.open(codebookPath)) {
      return loadCodebook((DataInputStream) fsIn);
    }
  }

  /**
   * Loads a {@link ProductQuantization} codebook from an input stream.
   * @param in the input stream
   * @return the deserialized {@link ProductQuantization} codebook
   * @throws IOException if reading fails
   */
  public static ProductQuantization loadCodebook(DataInputStream in) throws IOException {
    Preconditions.checkNotNull(in, "in must not be null");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) != -1) {
      baos.write(buffer, 0, read);
    }
    ByteBufferReader reader = new ByteBufferReader(ByteBuffer.wrap(baos.toByteArray()));
    return ProductQuantization.load(reader);
  }

  /**
   * Adds the codebook path to the Hadoop job's DistributedCache and sets the configuration key.
   * @param job          the MapReduce job to distribute the codebook to (may be null if configuring
   *                     conf directly)
   * @param codebookPath the HDFS path of the serialized codebook
   * @param conf         the job configuration
   */
  public static void distributeCodebook(Job job, Path codebookPath, Configuration conf) {
    Preconditions.checkNotNull(codebookPath, "codebookPath must not be null");
    if (job != null) {
      job.addCacheFile(codebookPath.toUri());
      PhoenixConfigurationUtil.setHnswPqCodebookPath(job.getConfiguration(),
        codebookPath.toString());
    }
    if (conf != null) {
      PhoenixConfigurationUtil.setHnswPqCodebookPath(conf, codebookPath.toString());
    }
  }

  /**
   * Bridge adapter converting a {@link DataOutputStream} into a JVector {@link IndexWriter}.
   */
  private static final class OutputStreamIndexWriter implements IndexWriter {

    private final DataOutputStream out;
    private long position;

    OutputStreamIndexWriter(OutputStream os) {
      this.out =
        (os instanceof DataOutputStream) ? (DataOutputStream) os : new DataOutputStream(os);
      this.position = 0;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      position++;
    }

    @Override
    public void write(byte[] b) throws IOException {
      out.write(b);
      position += b.length;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      position += len;
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
      out.writeBoolean(v);
      position += 1;
    }

    @Override
    public void writeByte(int v) throws IOException {
      out.writeByte(v);
      position += 1;
    }

    @Override
    public void writeShort(int v) throws IOException {
      out.writeShort(v);
      position += 2;
    }

    @Override
    public void writeChar(int v) throws IOException {
      out.writeChar(v);
      position += 2;
    }

    @Override
    public void writeInt(int v) throws IOException {
      out.writeInt(v);
      position += 4;
    }

    @Override
    public void writeLong(long v) throws IOException {
      out.writeLong(v);
      position += 8;
    }

    @Override
    public void writeFloat(float v) throws IOException {
      out.writeFloat(v);
      position += 4;
    }

    @Override
    public void writeDouble(double v) throws IOException {
      out.writeDouble(v);
      position += 8;
    }

    @Override
    public void writeBytes(String s) throws IOException {
      out.writeBytes(s);
      position += s.length();
    }

    @Override
    public void writeChars(String s) throws IOException {
      out.writeChars(s);
      position += s.length() * 2L;
    }

    @Override
    public void writeUTF(String s) throws IOException {
      int before = out.size();
      out.writeUTF(s);
      position += (out.size() - before);
    }

    @Override
    public void writeFloats(float[] floats, int offset, int length) throws IOException {
      for (int i = 0; i < length; i++) {
        out.writeFloat(floats[offset + i]);
      }
      position += length * 4L;
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }
}
