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
import org.apache.phoenix.vectorharness.config.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for published benchmark corpora, overlaying synthetic relational attributes onto
 * static vector datasets. Inserts and moves take real, unseen vectors from a held-out partition.
 * Local re-embeddings perturb the current vector of the row.
 */
public abstract class PublishedCorpus implements Corpus {

  private static final Logger LOG = LoggerFactory.getLogger(PublishedCorpus.class);

  /** Relative Gaussian noise scale for local vector perturbations. */
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

  /** Returns the total count of published queries. */
  protected abstract int queryCount();

  /** Returns the published query vector at index {@code q}. */
  protected abstract float[] queryVector(int q);

  /** Returns the count of available held out vectors. */
  protected abstract long heldOutCount();

  /**
   * Returns the count of rows to load from a published corpus. The count never exceeds the request,
   * and it never includes the held-out tail.
   * @param available the row count of the published corpus
   * @param requested the configured row count; 0 or more than available requests all rows
   * @param holdout   the row count of the tail that supplies new vectors
   * @param source    the corpus location for log and error messages
   * @throws IllegalArgumentException if the tail leaves no rows to load
   */
  static long loadedRows(long available, long requested, long holdout, Object source) {
    long n = requested <= 0 || requested > available ? available : requested;
    if (holdout <= 0) {
      return n;
    }
    long limit = available - holdout;
    if (limit <= 0) {
      throw new IllegalArgumentException(Setting.CORPUS_HOLDOUT_ROWS.key() + "=" + holdout
        + " holds out all " + available + " rows of " + source + ", so no rows remain to load");
    }
    if (n > limit) {
      LOG.info("Loading {} of the {} rows of {}; the last {} rows supply the new vectors", limit,
        available, source, holdout);
      return limit;
    }
    return n;
  }

  /** Returns the held-out vector at index {@code i}. */
  protected abstract float[] heldOutVector(long i);

  /** Incorporates published dataset files into the fingerprint digest. */
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

  /**
   * Takes the vectors of inserts and moves from the held-out partition. The draw index of the
   * operation selects the held-out row, so the row does not depend on thread timing. Distinct draw
   * indexes give distinct rows, and no held-out row is a loaded row. A draw past the partition
   * fails, and configuration validation prevents this case.
   */
  private final class HeldOutMutations implements MutationVectors {

    private float[] heldOut(long draw) {
      if (draw < 0) {
        throw new IllegalArgumentException(
          "A new vector of " + description() + " needs a draw index, but the index is " + draw);
      }
      long held = heldOutCount();
      if (draw >= held) {
        throw new IllegalStateException(
          "Draw " + draw + " is past the " + held + " held-out rows of " + description()
            + "; raise " + Setting.CORPUS_HOLDOUT_ROWS.key() + " or lower the mutation volume");
      }
      return heldOutVector(draw);
    }

    @Override
    public boolean finite() {
      return true;
    }

    @Override
    public Embedding insert(long ordinal, long draw, double minutes) {
      return new Embedding(heldOut(draw), -1);
    }

    @Override
    public Embedding reembedLocal(long ordinal, int cluster, float[] current, long seq,
      double minutes) {
      return new Embedding(perturbed(current, streams.at(RandomStreams.MUTATIONS, 2, seq)), -1);
    }

    @Override
    public Embedding move(long ordinal, int cluster, long seq, long draw, double minutes) {
      return new Embedding(heldOut(draw), -1);
    }

    @Override
    public double moveProbability(double baseFraction, double minutes) {
      return baseFraction;
    }
  }
}
