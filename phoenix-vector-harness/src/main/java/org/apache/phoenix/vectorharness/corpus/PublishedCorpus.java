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
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.vectorharness.config.Metric;

/**
 * Common behavior of the published benchmark corpora. The harness synthesizes relational attributes
 * from row ordinals with the same attribute model as synthetic corpora, so that filtered and
 * tenant-scoped evaluations run over the published vectors. Mutations of a static corpus substitute
 * held-out vectors: the learn partition if the corpus has one, else the base rows past the loaded
 * prefix, else perturbed copies of loaded rows.
 */
public abstract class PublishedCorpus implements Corpus {

  /** Relative deviation of the noise of a local re-embedding. */
  static final double LOCAL_NOISE = 0.02;

  protected final RandomStreams streams;
  protected final AttributeModel attributes;
  protected final long rows;
  protected final Metric metric;
  private final MutationVectors mutationVectors = new HeldOutMutations();
  private volatile String fingerprint;
  private volatile List<PanelQuery> panelCache;

  protected PublishedCorpus(RandomStreams streams, AttributeModel attributes, long rows,
    Metric metric) {
    this.streams = streams;
    this.attributes = attributes;
    this.rows = rows;
    this.metric = metric;
  }

  /** Returns the number of published queries. */
  protected abstract int queryCount();

  /** Returns published query q. */
  protected abstract float[] queryVector(int q);

  /** Returns the number of held-out vectors, which may be zero. */
  protected abstract long heldOutCount();

  /** Returns held-out vector i. */
  protected abstract float[] heldOutVector(long i);

  /** Adds the published files to a fingerprint digest. */
  protected abstract void digestFiles(MessageDigest md);

  @Override
  public long rows() {
    return rows;
  }

  @Override
  public Metric definedMetric() {
    return metric;
  }

  @Override
  public int clusterOf(long ordinal) {
    return -1;
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
    if (cached == null) {
      synchronized (this) {
        if (panelCache == null) {
          int n = queryCount();
          List<PanelQuery> list = new ArrayList<>(n);
          long seed = streams.streamSeed(RandomStreams.QUERIES);
          for (int q = 0; q < n; q++) {
            int tenant = attributes.tenants().sample(RandomStreams.atSeed(seed, q));
            list.add(new PanelQuery(q, queryVector(q), PanelQuery.Kind.PUBLISHED, tenant, -1));
          }
          panelCache = Collections.unmodifiableList(list);
        }
        cached = panelCache;
      }
    }
    return cached.subList(0, Math.min(size, cached.size()));
  }

  @Override
  public MutationVectors mutations() {
    return mutationVectors;
  }

  @Override
  public String fingerprint() {
    String f = fingerprint;
    if (f == null) {
      synchronized (this) {
        if (fingerprint == null) {
          MessageDigest md = Digests.sha256();
          Digests.update(md, parameters());
          digestFiles(md);
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

  private float[] perturbed(float[] v, SplitMix64 rnd) {
    double ss = 0.0;
    for (float x : v) {
      ss += (double) x * x;
    }
    double rms = Math.sqrt(ss / Math.max(1, v.length));
    double sd = LOCAL_NOISE * (rms > 0 ? rms : 1.0);
    float[] out = new float[v.length];
    for (int i = 0; i < v.length; i++) {
      out[i] = (float) (v[i] + sd * rnd.nextGaussian());
    }
    if (metric == Metric.COSINE) {
      SyntheticCorpus.normalizeInPlace(out);
    }
    return out;
  }

  /** Mutation vectors that substitute held-out vectors. */
  private final class HeldOutMutations implements MutationVectors {

    @Override
    public Embedding insert(long ordinal, double minutes) {
      SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, 1, ordinal);
      long held = heldOutCount();
      if (held > 0) {
        return new Embedding(heldOutVector(Math.floorMod(ordinal - rows, held)), -1);
      }
      // Reinsert a prior row with a small perturbation, so that it does not tie with the original.
      return new Embedding(perturbed(vector(rnd.nextLong(rows)), rnd), -1);
    }

    @Override
    public Embedding reembedLocal(long ordinal, int cluster, float[] current, long seq,
      double minutes) {
      return new Embedding(perturbed(current, streams.at(RandomStreams.MUTATIONS, 2, seq)), -1);
    }

    @Override
    public Embedding move(long ordinal, int cluster, long seq, double minutes) {
      SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, 3, seq);
      long held = heldOutCount();
      if (held > 0) {
        return new Embedding(heldOutVector(rnd.nextLong(held)), -1);
      }
      return new Embedding(perturbed(vector(rnd.nextLong(rows)), rnd), -1);
    }

    @Override
    public double moveProbability(double baseFraction, double minutes) {
      return baseFraction;
    }
  }
}
