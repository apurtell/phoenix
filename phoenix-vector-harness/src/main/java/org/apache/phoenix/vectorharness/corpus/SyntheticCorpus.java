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

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.phoenix.vectorharness.config.HarnessConfig.DriftMode;
import org.apache.phoenix.vectorharness.config.Metric;

/**
 * A parameterized Gaussian mixture with controllable geometry. Each component has a center drawn
 * from a standard normal in the ambient dimension D, and its points lie near a random subspace of
 * intrinsic dimension d through the center: a point is the center, plus a Gaussian combination of d
 * orthonormal basis vectors with per-direction deviation sigma, plus isotropic ambient noise. When
 * d equals D the component is isotropic. The deviations of the components are spread geometrically
 * so that the radius of the most diffuse component is {@code spread} times the radius of the
 * tightest. Component sizes are uniform or Zipf distributed. Vectors are optionally projected onto
 * the unit sphere.
 * <p>
 * The generator is a pure function of the master seed and the element ordinal: row i draws its
 * component and its point from element i of the docs stream, so rows can be generated in any order
 * by any number of threads.
 */
public final class SyntheticCorpus implements Corpus {

  /** Radius of the tightest component relative to sqrt(D); centers are about sqrt(2D) apart. */
  static final double TIGHTEST_RADIUS = 0.35;
  /** Largest number of floats the subspace bases may hold. */
  static final long MAX_BASIS_FLOATS = 64L * 1024 * 1024;
  /** Rows that the fingerprint digests. */
  static final int FINGERPRINT_SAMPLE_ROWS = 65536;
  /**
   * The correlation of a row with its previous vector under a local re-embedding. A step moves the
   * row by about sqrt(2 (1 - rho)) times its component radius, a tenth of it.
   */
  static final double LOCAL_RHO = 0.995;
  private static final double LOCAL_MIX = Math.sqrt(1.0 - LOCAL_RHO * LOCAL_RHO);
  /** The bits of a permutation sort key that hold the component index. */
  private static final long INDEX_BITS = 0x7FFFFFFFL;

  private final long seed;
  private final long rows;
  private final int dim;
  private final int clusters;
  private final double clusterZipf;
  private final double spread;
  private final int intrinsicDim;
  private final double noise;
  private final boolean normalize;
  private final double boundaryFraction;
  private final double oodFraction;
  private final DriftMode driftMode;
  private final double driftRate;
  private final Metric metric;

  private final RandomStreams streams;
  private final long docSeed;
  private final long querySeed;
  private final AttributeModel attributes;
  private final Vocabulary clusterWeights;
  private final int[] retirementRank;
  private final int[] slotOfRank;
  private final double rMin;
  private final double sigmaMin;
  private final Map<Integer, Component> components = new ConcurrentHashMap<>();
  private final MutationVectors mutationVectors = new SyntheticMutations();

  private volatile List<PanelQuery> panelCache = Collections.emptyList();
  private volatile String fingerprint;

  public SyntheticCorpus(long seed, long rows, int dim, int clusters, double clusterZipf,
    double spread, int intrinsicDim, double noise, boolean normalize, double boundaryFraction,
    double oodFraction, DriftMode driftMode, double driftRate, Metric metric,
    AttributeModel attributes) {
    if (rows <= 0 || dim <= 0 || clusters <= 0) {
      throw new IllegalArgumentException("rows, dim, and clusters must be positive");
    }
    if (intrinsicDim < 1 || intrinsicDim > dim) {
      throw new IllegalArgumentException("intrinsic dimension must be in [1, dim]");
    }
    if (intrinsicDim < dim && (long) clusters * 2 * intrinsicDim * dim > MAX_BASIS_FLOATS) {
      throw new IllegalArgumentException(String.format(Locale.ROOT,
        "The subspace bases of %d components of rank %d in dimension %d need %d floats; reduce"
          + " corpus.clusters or corpus.intrinsic_dim",
        clusters, intrinsicDim, dim, (long) clusters * 2 * intrinsicDim * dim));
    }
    this.seed = seed;
    this.rows = rows;
    this.dim = dim;
    this.clusters = clusters;
    this.clusterZipf = clusterZipf;
    this.spread = spread;
    this.intrinsicDim = intrinsicDim;
    this.noise = noise;
    this.normalize = normalize;
    this.boundaryFraction = boundaryFraction;
    this.oodFraction = oodFraction;
    this.driftMode = driftMode;
    this.driftRate = driftRate;
    this.metric = metric;
    this.streams = new RandomStreams(seed);
    this.docSeed = streams.streamSeed(RandomStreams.DOCS);
    this.querySeed = streams.streamSeed(RandomStreams.QUERIES);
    this.attributes = attributes;
    this.clusterWeights = new Vocabulary("k", clusters, clusterZipf);
    this.rMin = TIGHTEST_RADIUS * Math.sqrt(dim);
    this.sigmaMin = rMin / Math.sqrt(intrinsicDim);
    // A deterministic permutation of the components orders their deviations and retirements. The
    // low 31 bits of a sort key hold the component index, so that any int count decodes exactly.
    long[] keys = new long[clusters];
    long clusterSeed = streams.streamSeed(RandomStreams.CLUSTERS);
    for (int c = 0; c < clusters; c++) {
      keys[c] = (SplitMix64.combine(clusterSeed, c) & ~INDEX_BITS) | c;
    }
    long[] sorted = keys.clone();
    Arrays.sort(sorted);
    retirementRank = new int[clusters];
    slotOfRank = new int[clusters];
    for (int r = 0; r < clusters; r++) {
      int slot = (int) (sorted[r] & INDEX_BITS);
      retirementRank[slot] = r;
      slotOfRank[r] = slot;
    }
  }

  @Override
  public String description() {
    return String.format(Locale.ROOT, "synthetic:gmm rows=%d dim=%d clusters=%d intrinsic=%d", rows,
      dim, clusters, intrinsicDim);
  }

  @Override
  public String sourceName() {
    return "synthetic:gmm";
  }

  @Override
  public int dim() {
    return dim;
  }

  @Override
  public long rows() {
    return rows;
  }

  @Override
  public Metric definedMetric() {
    return null;
  }

  /** Returns the metric the corpus was generated for, which decides the default normalization. */
  public Metric metric() {
    return metric;
  }

  public boolean normalized() {
    return normalize;
  }

  @Override
  public float[] vector(long ordinal) {
    checkOrdinal(ordinal);
    SplitMix64 rnd = RandomStreams.atSeed(docSeed, ordinal);
    int c = clusterWeights.sample(rnd);
    return component(c).point(rnd, 0.0);
  }

  @Override
  public int clusterOf(long ordinal) {
    checkOrdinal(ordinal);
    return clusterWeights.sample(RandomStreams.atSeed(docSeed, ordinal));
  }

  @Override
  public DocAttributes attributes(long ordinal) {
    return attributes.attributes(ordinal);
  }

  @Override
  public AttributeModel attributeModel() {
    return attributes;
  }

  @Override
  public List<PanelQuery> panel(int size) {
    List<PanelQuery> cached = panelCache;
    if (cached.size() >= size) {
      return cached.subList(0, size);
    }
    synchronized (this) {
      cached = panelCache;
      if (cached.size() >= size) {
        return cached.subList(0, size);
      }
      List<PanelQuery> list = new ArrayList<>(size);
      list.addAll(cached);
      for (int q = cached.size(); q < size; q++) {
        list.add(panelQuery(q));
      }
      panelCache = Collections.unmodifiableList(list);
      return panelCache;
    }
  }

  private PanelQuery panelQuery(int q) {
    SplitMix64 rnd = RandomStreams.atSeed(querySeed, q);
    double u = rnd.nextDouble();
    int tenant = attributes.tenants().sample(rnd);
    float[] v;
    PanelQuery.Kind kind;
    int cluster = -1;
    if (u < boundaryFraction && clusters > 1) {
      kind = PanelQuery.Kind.BOUNDARY;
      int a = clusterWeights.sample(rnd);
      int b = (a + 1 + rnd.nextInt(clusters - 1)) % clusters;
      double t = 0.35 + 0.3 * rnd.nextDouble();
      float[] ca = component(a).center;
      float[] cb = component(b).center;
      v = new float[dim];
      for (int i = 0; i < dim; i++) {
        v[i] = (float) ((1 - t) * ca[i] + t * cb[i] + noise * sigmaMin * rnd.nextGaussian());
      }
      if (normalize) {
        normalizeInPlace(v);
      }
    } else if (u >= boundaryFraction && u < boundaryFraction + oodFraction) {
      // With a single cluster a boundary draw falls through to an in-distribution query, not
      // to an out-of-distribution one, so that the OOD share stays as configured.
      kind = PanelQuery.Kind.OUT_OF_DISTRIBUTION;
      // A fresh center drawn like the component centers is about as far from every component as
      // the components are from each other.
      v = new float[dim];
      for (int i = 0; i < dim; i++) {
        v[i] = (float) (rnd.nextGaussian() + sigmaMin * rnd.nextGaussian());
      }
      if (normalize) {
        normalizeInPlace(v);
      }
    } else {
      kind = PanelQuery.Kind.IN_DISTRIBUTION;
      cluster = clusterWeights.sample(rnd);
      v = component(cluster).point(rnd, 0.0);
    }
    return new PanelQuery(q, v, kind, tenant, cluster);
  }

  @Override
  public int[][] publishedNeighbors() {
    return null;
  }

  @Override
  public MutationVectors mutations() {
    return mutationVectors;
  }

  @Override
  public Map<String, String> parameters() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("source", sourceName());
    m.put("generator.version", "1");
    m.put("seed", Long.toString(seed));
    m.put("rows", Long.toString(rows));
    m.put("dim", Integer.toString(dim));
    m.put("clusters", Integer.toString(clusters));
    m.put("cluster.zipf", Double.toString(clusterZipf));
    m.put("variance.spread", Double.toString(spread));
    m.put("intrinsic_dim", Integer.toString(intrinsicDim));
    m.put("noise", Double.toString(noise));
    m.put("normalize", Boolean.toString(normalize));
    m.put("query.boundary_fraction", Double.toString(boundaryFraction));
    m.put("query.ood_fraction", Double.toString(oodFraction));
    m.put("drift.mode", driftMode.name());
    m.put("drift.rate", Double.toString(driftRate));
    for (Map.Entry<String, String> e : attributes.parameters().entrySet()) {
      m.put("attr." + e.getKey(), e.getValue());
    }
    return m;
  }

  /**
   * Returns the parameters that define the rows: the parameters less those of the panel and the
   * drift, which only shape the queries and the mutations of a run, so that a loaded table serves
   * runs that vary them. The panel has its own digest.
   */
  private Map<String, String> rowParameters() {
    Map<String, String> m = new LinkedHashMap<>(parameters());
    m.keySet().removeIf(k -> k.startsWith("query.") || k.startsWith("drift."));
    return m;
  }

  @Override
  public String fingerprint() {
    String f = fingerprint;
    if (f == null) {
      synchronized (this) {
        if (fingerprint == null) {
          MessageDigest md = Digests.sha256();
          Digests.update(md, rowParameters());
          long n = Math.min(rows, FINGERPRINT_SAMPLE_ROWS);
          for (long i = 0; i < n; i++) {
            long ordinal = n == rows ? i : (long) ((double) i * rows / n);
            Digests.update(md, vector(ordinal));
            Digests.update(md, attributes(ordinal));
          }
          fingerprint = Digests.hex(md.digest());
        }
        f = fingerprint;
      }
    }
    return f;
  }

  @Override
  public String panelDigest(int size) {
    MessageDigest md = Digests.sha256();
    for (PanelQuery q : panel(size)) {
      Digests.update(md, q.vector());
      Digests.update(md, q.tenant());
    }
    return Digests.hex(md.digest());
  }

  @Override
  public void close() {
  }

  private void checkOrdinal(long ordinal) {
    if (ordinal < 0 || ordinal >= rows) {
      throw new IndexOutOfBoundsException("Row ordinal " + ordinal + " outside [0, " + rows + ")");
    }
  }

  /**
   * Returns component c. Components 0 to clusters-1 are the mixture; components from clusters
   * upward are born by birth-and-death drift, component clusters + j replacing the component of
   * retirement rank j.
   */
  Component component(int c) {
    Component comp = components.get(c);
    if (comp == null) {
      comp = components.computeIfAbsent(c, this::newComponent);
    }
    return comp;
  }

  private Component newComponent(int c) {
    SplitMix64 rnd = streams.at(RandomStreams.CLUSTERS, c, 0);
    float[] center = new float[dim];
    for (int i = 0; i < dim; i++) {
      center[i] = (float) rnd.nextGaussian();
    }
    // Deviation rank: original components use their permutation rank; born components inherit the
    // rank of the component they replace.
    int rank = c < clusters ? retirementRank[c] : (c - clusters) % clusters;
    double u = clusters > 1 ? (double) rank / (clusters - 1) : 0.0;
    double sigma = sigmaMin * Math.pow(spread, u);
    float[][] basis = null;
    if (intrinsicDim < dim) {
      basis = orthonormalBasis(rnd);
    }
    float[] driftDir = null;
    if (driftMode == DriftMode.MIGRATE) {
      SplitMix64 d = streams.at(RandomStreams.DRIFT, c, 0);
      driftDir = new float[dim];
      for (int i = 0; i < dim; i++) {
        driftDir[i] = (float) d.nextGaussian();
      }
      normalizeInPlace(driftDir);
    }
    return new Component(c, center, sigma, basis, driftDir);
  }

  private float[][] orthonormalBasis(SplitMix64 rnd) {
    double[][] b = new double[intrinsicDim][dim];
    for (int j = 0; j < intrinsicDim; j++) {
      double[] v = b[j];
      for (int i = 0; i < dim; i++) {
        v[i] = rnd.nextGaussian();
      }
      // Modified Gram-Schmidt against the previous basis vectors.
      for (int k = 0; k < j; k++) {
        double[] w = b[k];
        double dot = 0.0;
        for (int i = 0; i < dim; i++) {
          dot += v[i] * w[i];
        }
        for (int i = 0; i < dim; i++) {
          v[i] -= dot * w[i];
        }
      }
      double norm = 0.0;
      for (int i = 0; i < dim; i++) {
        norm += v[i] * v[i];
      }
      norm = Math.sqrt(norm);
      if (norm < 1e-9) {
        // Degenerate draw; redraw this basis vector.
        j--;
        continue;
      }
      for (int i = 0; i < dim; i++) {
        v[i] /= norm;
      }
    }
    float[][] out = new float[intrinsicDim][dim];
    for (int j = 0; j < intrinsicDim; j++) {
      for (int i = 0; i < dim; i++) {
        out[j][i] = (float) b[j][i];
      }
    }
    return out;
  }

  static void normalizeInPlace(float[] v) {
    double norm = 0.0;
    for (float x : v) {
      norm += (double) x * x;
    }
    norm = Math.sqrt(norm);
    if (norm > 0) {
      for (int i = 0; i < v.length; i++) {
        v[i] = (float) (v[i] / norm);
      }
    }
  }

  /** The number of original components retired, and born replacements, at a drift time. */
  int turnover(double minutes) {
    if (driftMode != DriftMode.BIRTH_DEATH || driftRate <= 0 || minutes <= 0) {
      return 0;
    }
    return (int) Math.min(clusters, Math.floor(minutes * clusters * driftRate));
  }

  /** Draws an active component at a drift time. */
  int sampleActive(SplitMix64 rnd, double minutes) {
    return activeAt(clusterWeights.sample(rnd), turnover(minutes));
  }

  /** Returns the active component of a slot after k retirements: itself, or its replacement. */
  private int activeAt(int slot, int k) {
    if (k > 0 && retirementRank[slot] < k) {
      return clusters + retirementRank[slot];
    }
    return slot;
  }

  /** Returns the retirement rank of a slot, which also orders the deviations. */
  int retirementRank(int slot) {
    return retirementRank[slot];
  }

  /** Returns the slot of a component: its own index, or that of the component it replaced. */
  private int slotOf(int component) {
    return component < clusters ? component : slotOfRank[component - clusters];
  }

  /** One mixture component. */
  final class Component {
    final int id;
    final float[] center;
    final double sigma;
    final float[][] basis;
    final float[] driftDir;

    Component(int id, float[] center, double sigma, float[][] basis, float[] driftDir) {
      this.id = id;
      this.center = center;
      this.sigma = sigma;
      this.basis = basis;
      this.driftDir = driftDir;
    }

    /** Returns the migration of the center at a drift time, along driftDir. */
    private double shift(double minutes) {
      if (driftDir != null && driftRate > 0 && minutes > 0) {
        return driftRate * minutes * rMin;
      }
      return 0.0;
    }

    float[] point(SplitMix64 rnd, double minutes) {
      float[] x = new float[dim];
      double shift = shift(minutes);
      if (basis == null) {
        for (int i = 0; i < dim; i++) {
          x[i] = (float) (center[i] + sigma * rnd.nextGaussian());
        }
      } else {
        double[] acc = new double[dim];
        for (int i = 0; i < dim; i++) {
          acc[i] = center[i];
        }
        for (int j = 0; j < basis.length; j++) {
          double z = sigma * rnd.nextGaussian();
          float[] b = basis[j];
          for (int i = 0; i < dim; i++) {
            acc[i] += z * b[i];
          }
        }
        double ns = noise * sigmaMin;
        for (int i = 0; i < dim; i++) {
          x[i] = (float) (acc[i] + ns * rnd.nextGaussian());
        }
      }
      if (shift != 0.0) {
        for (int i = 0; i < dim; i++) {
          x[i] = (float) (x[i] + shift * driftDir[i]);
        }
      }
      if (normalize) {
        normalizeInPlace(x);
      }
      return x;
    }

    /**
     * Returns a local re-embedding of a point of this component: one step of a first order
     * autoregressive process toward a fresh point f, {@code m + rho (current - m) + sqrt(1 -
     * rho^2) (f - m)} around the mean m of the component at the drift time. The step moves the
     * point by a tenth of the component radius and keeps the mean and the covariance of the
     * component, so repeated re-embeddings neither shrink nor spread it, and a row written before a
     * migration follows its component gradually. On the unit sphere m is the mean of the normalized
     * points, which is about the center scaled by 1 / sqrt(|c|^2 + E|x - c|^2), and the step is
     * projected back onto the sphere.
     */
    float[] perturb(float[] current, SplitMix64 rnd, double minutes) {
      float[] fresh = point(rnd, minutes);
      double shift = shift(minutes);
      double[] m = new double[dim];
      double norm2 = 0.0;
      for (int i = 0; i < dim; i++) {
        m[i] = center[i] + (shift == 0.0 ? 0.0 : shift * driftDir[i]);
        norm2 += m[i] * m[i];
      }
      if (normalize) {
        double ns = noise * sigmaMin;
        double spread2 =
          basis == null ? sigma * sigma * dim : sigma * sigma * basis.length + ns * ns * dim;
        double scale = 1.0 / Math.sqrt(norm2 + spread2);
        for (int i = 0; i < dim; i++) {
          m[i] *= scale;
        }
      }
      float[] x = new float[dim];
      for (int i = 0; i < dim; i++) {
        x[i] = (float) (m[i] + LOCAL_RHO * (current[i] - m[i]) + LOCAL_MIX * (fresh[i] - m[i]));
      }
      if (normalize) {
        normalizeInPlace(x);
      }
      return x;
    }
  }

  /** Mutation vectors of the mixture, under its drift model. */
  private final class SyntheticMutations implements MutationVectors {

    private static final long INSERT = 1;
    private static final long LOCAL = 2;
    private static final long MOVE = 3;

    @Override
    public Embedding insert(long ordinal, double minutes) {
      SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, INSERT, ordinal);
      int c = sampleActive(rnd, minutes);
      return new Embedding(component(c).point(rnd, minutes), c);
    }

    /**
     * Perturbs the current vector within its cluster by a small step; a row of unknown cluster or
     * vector gets a fresh point of a cluster instead.
     */
    @Override
    public Embedding reembedLocal(long ordinal, int cluster, float[] current, long seq,
      double minutes) {
      SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, LOCAL, seq);
      if (cluster >= 0 && current != null && current.length == dim) {
        return new Embedding(component(cluster).perturb(current, rnd, minutes), cluster);
      }
      int c = cluster >= 0 ? cluster : sampleActive(rnd, minutes);
      return new Embedding(component(c).point(rnd, minutes), c);
    }

    @Override
    public Embedding move(long ordinal, int cluster, long seq, double minutes) {
      SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, MOVE, seq);
      int c = sampleActive(rnd, minutes);
      for (int attempt = 0; c == cluster && attempt < 16; attempt++) {
        c = sampleActive(rnd, minutes);
      }
      if (c == cluster && clusters > 1) {
        // The current component holds most of the weight. Take the active component of the next
        // slot, which is never the current one, since slots map to active components one to one.
        c = activeAt((slotOf(cluster) + 1) % clusters, turnover(minutes));
      }
      return new Embedding(component(c).point(rnd, minutes), c);
    }

    @Override
    public double moveProbability(double baseFraction, double minutes) {
      if (driftMode == DriftMode.REASSIGN && driftRate > 0 && minutes > 0) {
        return Math.min(1.0, baseFraction + driftRate * minutes);
      }
      return baseFraction;
    }
  }
}
