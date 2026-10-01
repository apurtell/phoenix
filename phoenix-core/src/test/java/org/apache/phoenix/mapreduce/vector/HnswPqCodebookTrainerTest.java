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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.ByteSequence;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link HnswPqCodebookTrainer}.
 * <p>
 * Tests codebook training dimensions, serialization round-trip byte-level equality, quantization
 * distortion efficacy, and nearest-neighbor recall (>= 0.85) on realistic structured embeddings
 * generated via character 3-gram feature hashing.
 * </p>
 */
public class HnswPqCodebookTrainerTest {

  private static final VectorTypeSupport VTS =
    VectorizationProvider.getInstance().getVectorTypeSupport();

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  /**
   * Minimal real embedding generator. Produces realistic 128-dimensional L2-normalized vectors from
   * multi-domain text phrases via character 3-gram feature hashing.
   */
  private static final class MinimalTextEmbedder {

    private static final String[][] TOPIC_DICTIONARIES = {
      // Topic 0: Database internals & query optimization
      { "relational", "database", "query", "index", "plan", "optimizer", "btree", "lsm", "wal",
        "projection", "predicate", "statistics", "metadata", "catalog", "table" },
      // Topic 1: Distributed consensus & replication
      { "distributed", "consensus", "leader", "follower", "heartbeat", "election", "quorum",
        "paxos", "raft", "zookeeper", "replication", "consistency", "partition", "epoch" },
      // Topic 2: Vector search & similarity algorithms
      { "vector", "search", "nearest", "neighbor", "hnsw", "graph", "voronoi", "quantization",
        "cosine", "euclidean", "similarity", "embedding", "distance", "metric", "subspace" },
      // Topic 3: Operating systems & kernel internals
      { "operating", "system", "kernel", "thread", "process", "virtual", "memory", "paging",
        "interrupt", "scheduling", "switch", "descriptor", "syscall", "mutex", "semaphore" },
      // Topic 4: Network protocols & transport
      { "network", "protocol", "ethernet", "packet", "routing", "socket", "handshake", "congestion",
        "window", "bandwidth", "latency", "transport", "datagram", "gateway" },
      // Topic 5: Astronomy & astrophysics
      { "astronomy", "cosmology", "galaxy", "nebula", "supernova", "blackhole", "horizon",
        "gravitation", "stellar", "orbit", "telescope", "spectroscopy", "lightyear", "pulsar" },
      // Topic 6: Molecular biology & genetics
      { "cellular", "biology", "ribosome", "mitochondria", "chloroplast", "transcription",
        "translation", "polypeptide", "enzyme", "membrane", "nucleotide", "lipid", "protein" },
      // Topic 7: Organic chemistry & materials
      { "organic", "chemistry", "covalent", "bond", "hydrocarbon", "aromatic", "ester", "alcohol",
        "carboxylic", "isomer", "chirality", "valence", "polymer", "synthesis" },
      // Topic 8: Music theory & acoustics
      { "music", "acoustic", "orchestra", "symphony", "chromatic", "scale", "counterpoint",
        "harmonic", "cadence", "allegro", "timbre", "resonance", "overtone", "polyphony" },
      // Topic 9: Classical architecture & structural engineering
      { "architecture", "cantilever", "colonnade", "vault", "buttress", "entablature", "architrave",
        "masonry", "truss", "bearing", "dome", "facade", "pediment", "corbel" } };

    static List<float[]> generateDataset(int totalVectors, int dimension, long seed) {
      Random rng = new Random(seed);
      List<float[]> dataset = new ArrayList<>(totalVectors);
      int topics = TOPIC_DICTIONARIES.length;
      int perTopic = totalVectors / topics;

      for (int t = 0; t < topics; t++) {
        String[] vocab = TOPIC_DICTIONARIES[t];
        for (int i = 0; i < perTopic; i++) {
          StringBuilder phrase = new StringBuilder();
          int wordsInPhrase = 4 + rng.nextInt(3);
          for (int w = 0; w < wordsInPhrase; w++) {
            phrase.append(vocab[rng.nextInt(vocab.length)]).append(" ");
          }
          dataset.add(embed(phrase.toString(), dimension));
        }
      }

      // Add any remainder
      while (dataset.size() < totalVectors) {
        String[] vocab = TOPIC_DICTIONARIES[rng.nextInt(topics)];
        StringBuilder phrase = new StringBuilder();
        for (int w = 0; w < 5; w++) {
          phrase.append(vocab[rng.nextInt(vocab.length)]).append(" ");
        }
        dataset.add(embed(phrase.toString(), dimension));
      }

      return dataset;
    }

    static List<float[]> generateQueries(int numQueries, int dimension, long seed) {
      Random rng = new Random(seed);
      List<float[]> queries = new ArrayList<>(numQueries);
      int topics = TOPIC_DICTIONARIES.length;
      for (int i = 0; i < numQueries; i++) {
        String[] vocab = TOPIC_DICTIONARIES[i % topics];
        StringBuilder phrase = new StringBuilder();
        int wordsInPhrase = 3 + rng.nextInt(3);
        for (int w = 0; w < wordsInPhrase; w++) {
          phrase.append(vocab[rng.nextInt(vocab.length)]).append(" ");
        }
        queries.add(embed(phrase.toString(), dimension));
      }
      return queries;
    }

    static float[] embed(String text, int dimension) {
      float[] vec = new float[dimension];
      String clean = text.toLowerCase(Locale.ROOT).trim();
      if (clean.length() < 3) {
        clean = "   " + clean + "   ";
      }

      int len = clean.length() - 2;
      for (int i = 0; i < len; i++) {
        String trigram = clean.substring(i, i + 3);
        int h = trigram.hashCode();
        int index = Math.abs(h % dimension);
        float sign = ((h & 0x10000) != 0) ? 1.0f : -1.0f;
        vec[index] += sign;
      }

      // L2 Normalize
      float sumSq = 0.0f;
      for (float v : vec) {
        sumSq += v * v;
      }
      if (sumSq > 1e-12f) {
        float inv = (float) (1.0 / Math.sqrt(sumSq));
        for (int i = 0; i < dimension; i++) {
          vec[i] *= inv;
        }
      }
      return vec;
    }
  }

  @Test
  public void testCodebookDimensions() {
    int dimension = 128;
    int pqSegments = 16;
    List<float[]> dataset = MinimalTextEmbedder.generateDataset(600, dimension, 42L);

    ProductQuantization pq = HnswPqCodebookTrainer.trainCodebook(dataset, dimension, pqSegments);

    assertNotNull(pq);
    assertEquals(pqSegments, pq.getSubspaceCount());
    assertEquals(HnswPqCodebookTrainer.DEFAULT_CLUSTER_COUNT, pq.getClusterCount());
    assertEquals(dimension, pq.getOriginalDimension());
    assertEquals(pqSegments, pq.compressedVectorSize());
  }

  @Test
  public void testCodebookSerializationRoundTrip() throws IOException {
    int dimension = 128;
    int pqSegments = 16;
    List<float[]> dataset = MinimalTextEmbedder.generateDataset(600, dimension, 42L);
    ProductQuantization pqOriginal =
      HnswPqCodebookTrainer.trainCodebook(dataset, dimension, pqSegments);

    Configuration conf = new Configuration();
    File file1 = tempFolder.newFile("codebook1.bin");
    File file2 = tempFolder.newFile("codebook2.bin");
    Path path1 = new Path(file1.toURI());
    Path path2 = new Path(file2.toURI());

    // 1. Serialize original codebook to file
    HnswPqCodebookTrainer.serializeCodebook(pqOriginal, path1, conf);
    byte[] bytes1 = Files.readAllBytes(file1.toPath());
    assertTrue(bytes1.length > 0);

    // 2. Load codebook back from file
    ProductQuantization pqLoaded = HnswPqCodebookTrainer.loadCodebook(path1, conf);
    assertNotNull(pqLoaded);

    // 3. Serialize loaded codebook to second file and assert byte-level equality
    HnswPqCodebookTrainer.serializeCodebook(pqLoaded, path2, conf);
    byte[] bytes2 = Files.readAllBytes(file2.toPath());
    assertArrayEquals("Serialized codebooks must be byte-level equal", bytes1, bytes2);

    // 4. Test stream serialization round-trip
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(baos)) {
      HnswPqCodebookTrainer.serializeCodebook(pqOriginal, dos);
    }
    byte[] streamBytes = baos.toByteArray();
    assertArrayEquals("Stream serialization must match file serialization", bytes1, streamBytes);

    ProductQuantization pqFromStream;
    try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(streamBytes))) {
      pqFromStream = HnswPqCodebookTrainer.loadCodebook(dis);
    }
    assertNotNull(pqFromStream);

    // 5. Functional equivalence: encode & decode sample vectors
    VectorFloat<?> scratch1 = VTS.createFloatVector(new float[dimension]);
    VectorFloat<?> scratch2 = VTS.createFloatVector(new float[dimension]);
    for (int i = 0; i < 20; i++) {
      VectorFloat<?> v = VTS.createFloatVector(dataset.get(i));
      ByteSequence<?> codeOrig = pqOriginal.encode(v);
      ByteSequence<?> codeLoaded = pqLoaded.encode(v);
      ByteSequence<?> codeStream = pqFromStream.encode(v);

      assertEquals(codeOrig.length(), codeLoaded.length());
      assertEquals(codeOrig.length(), codeStream.length());
      for (int b = 0; b < codeOrig.length(); b++) {
        assertEquals(codeOrig.get(b), codeLoaded.get(b));
        assertEquals(codeOrig.get(b), codeStream.get(b));
      }

      pqOriginal.decode(codeOrig, scratch1);
      pqLoaded.decode(codeLoaded, scratch2);
      for (int d = 0; d < dimension; d++) {
        assertEquals(scratch1.get(d), scratch2.get(d), 1e-6f);
      }
    }
  }

  @Test
  public void testQuantizationEfficacyAndRecall() {
    int dimension = 128;
    int pqSegments = 16;
    int datasetSize = 600;
    int queryCount = 50;
    int topK = 10;

    List<float[]> dataset = MinimalTextEmbedder.generateDataset(datasetSize, dimension, 100L);
    List<float[]> queries = MinimalTextEmbedder.generateQueries(queryCount, dimension, 200L);

    ProductQuantization pq = HnswPqCodebookTrainer.trainCodebook(dataset, dimension, pqSegments);

    List<VectorFloat<?>> datasetVectors = new ArrayList<>(datasetSize);
    List<VectorFloat<?>> decodedVectors = new ArrayList<>(datasetSize);
    double totalCosineFidelity = 0.0;

    for (int i = 0; i < datasetSize; i++) {
      VectorFloat<?> orig = VTS.createFloatVector(dataset.get(i));
      datasetVectors.add(orig);

      ByteSequence<?> code = pq.encode(orig);
      VectorFloat<?> decoded = VTS.createFloatVector(new float[dimension]);
      pq.decode(code, decoded);
      decodedVectors.add(decoded);

      float cosSim = VectorSimilarityFunction.COSINE.compare(orig, decoded);
      totalCosineFidelity += cosSim;
    }

    double meanCosineFidelity = totalCosineFidelity / datasetSize;
    assertTrue("PQ reconstruction fidelity was " + meanCosineFidelity + ", expected >= 0.90",
      meanCosineFidelity >= 0.90);

    // Evaluate Top-10 Recall comparing exact uncompressed search vs PQ-decoded search
    double totalRecall = 0.0;

    for (int q = 0; q < queryCount; q++) {
      VectorFloat<?> queryVec = VTS.createFloatVector(queries.get(q));

      // 1. Ground truth top-10 using exact uncompressed cosine similarity
      List<Candidate> exactCandidates = new ArrayList<>(datasetSize);
      for (int i = 0; i < datasetSize; i++) {
        float sim = VectorSimilarityFunction.COSINE.compare(queryVec, datasetVectors.get(i));
        exactCandidates.add(new Candidate(i, sim));
      }
      exactCandidates.sort(Comparator.comparingDouble(Candidate::getSimilarity).reversed());
      Set<Integer> groundTruth = new HashSet<>();
      for (int k = 0; k < topK; k++) {
        groundTruth.add(exactCandidates.get(k).getIndex());
      }

      // 2. PQ top-10 using decoded vectors
      List<Candidate> pqCandidates = new ArrayList<>(datasetSize);
      for (int i = 0; i < datasetSize; i++) {
        float sim = VectorSimilarityFunction.COSINE.compare(queryVec, decodedVectors.get(i));
        pqCandidates.add(new Candidate(i, sim));
      }
      pqCandidates.sort(Comparator.comparingDouble(Candidate::getSimilarity).reversed());
      Set<Integer> pqTopK = new HashSet<>();
      for (int k = 0; k < topK; k++) {
        pqTopK.add(pqCandidates.get(k).getIndex());
      }

      // 3. Compute overlap (Recall@10)
      pqTopK.retainAll(groundTruth);
      double recall = (double) pqTopK.size() / topK;
      totalRecall += recall;
    }

    double meanRecall = totalRecall / queryCount;
    assertTrue("PQ nearest-neighbor recall was " + meanRecall + ", expected >= 0.85",
      meanRecall >= 0.85);
  }

  @Test
  public void testQuantizedStorageSavings() throws IOException {
    int dimension = 128;
    int pqSegments = 16;
    List<float[]> dataset = MinimalTextEmbedder.generateDataset(600, dimension, 42L);
    ProductQuantization pq = HnswPqCodebookTrainer.trainCodebook(dataset, dimension, pqSegments);

    // 1. Per-vector footprint comparison (128 floats * 4 bytes = 512 bytes vs 16 bytes PQ codes)
    int unquantizedVectorBytes = dimension * Float.BYTES;
    int quantizedVectorBytes = pq.compressedVectorSize();
    assertEquals(512, unquantizedVectorBytes);
    assertEquals(16, quantizedVectorBytes);

    double perVectorCompressionRatio = (double) unquantizedVectorBytes / quantizedVectorBytes;
    assertEquals(32.0, perVectorCompressionRatio, 1e-6);

    // 2. Measure actual serialized global codebook size
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(baos)) {
      HnswPqCodebookTrainer.serializeCodebook(pq, dos);
    }
    long codebookSizeBytes = baos.size();
    assertTrue("Codebook size should be around 131 KB, was " + codebookSizeBytes,
      codebookSizeBytes > 100_000 && codebookSizeBytes < 150_000);

    // 3. Break-even dataset size where codebook overhead is fully amortized
    long breakEvenN = (codebookSizeBytes / (unquantizedVectorBytes - quantizedVectorBytes)) + 1;
    assertTrue("Break-even vector count should be under 300, was " + breakEvenN, breakEvenN < 300);

    // 4. Validate total storage savings (quantized vectors + codebook) at realistic dataset scales
    int[] scalePoints = { 1_000, 5_000, 10_000, 50_000 };
    double[] expectedMinSavingsRatio = { 3.0, 10.0, 15.0, 25.0 };

    for (int i = 0; i < scalePoints.length; i++) {
      int n = scalePoints[i];
      long totalUnquantizedBytes = (long) n * unquantizedVectorBytes;
      long totalQuantizedBytes = codebookSizeBytes + ((long) n * quantizedVectorBytes);

      assertTrue("Total quantized storage (" + totalQuantizedBytes
        + ") must be less than unquantized (" + totalUnquantizedBytes + ") at N=" + n,
        totalQuantizedBytes < totalUnquantizedBytes);

      double savingsRatio = (double) totalUnquantizedBytes / totalQuantizedBytes;
      assertTrue("Expected savings ratio >= " + expectedMinSavingsRatio[i] + " at N=" + n
        + ", but got " + savingsRatio, savingsRatio >= expectedMinSavingsRatio[i]);
    }
  }

  @Test
  public void testDistributeCodebook() {
    // A bare mock returns null from getConfiguration(), so stub it with a real Configuration:
    // distributeCodebook writes the codebook path into the job's own configuration, which is what
    // the mappers actually read.
    Job mockJob = mock(Job.class);
    Configuration jobConf = new Configuration();
    when(mockJob.getConfiguration()).thenReturn(jobConf);
    Configuration conf = new Configuration();
    Path codebookPath = new Path("hdfs://localhost:9000/tmp/test_codebook");

    HnswPqCodebookTrainer.distributeCodebook(mockJob, codebookPath, conf);

    assertEquals(codebookPath.toString(), PhoenixConfigurationUtil.getHnswPqCodebookPath(conf));
    assertEquals(codebookPath.toString(), PhoenixConfigurationUtil.getHnswPqCodebookPath(jobConf));
    verify(mockJob).addCacheFile(codebookPath.toUri());
  }

  @Test
  public void testValidationGuards() {
    try {
      HnswPqCodebookTrainer.trainCodebook((List<float[]>) null, 128, 16);
      fail("Expected NullPointerException on null samples");
    } catch (NullPointerException expected) {
      // Expected
    }

    try {
      HnswPqCodebookTrainer.trainCodebook(Collections.emptyList(), 128, 16);
      fail("Expected IllegalArgumentException on empty samples");
    } catch (IllegalArgumentException expected) {
      // Expected
    }

    try {
      List<float[]> samples = Collections.singletonList(new float[128]);
      HnswPqCodebookTrainer.trainCodebook(samples, 128, 17); // 128 % 17 != 0
      fail("Expected IllegalArgumentException when dimension is not divisible by segments");
    } catch (IllegalArgumentException expected) {
      // Expected
    }

    try {
      List<float[]> samples = Collections.singletonList(new float[64]);
      HnswPqCodebookTrainer.trainCodebook(samples, 128, 16); // Mismatched dimension
      fail("Expected IllegalArgumentException on dimension mismatch");
    } catch (IllegalArgumentException expected) {
      // Expected
    }
  }

  private static final class Candidate {
    private final int index;
    private final float similarity;

    Candidate(int index, float similarity) {
      this.index = index;
      this.similarity = similarity;
    }

    int getIndex() {
      return index;
    }

    float getSimilarity() {
      return similarity;
    }
  }
}
