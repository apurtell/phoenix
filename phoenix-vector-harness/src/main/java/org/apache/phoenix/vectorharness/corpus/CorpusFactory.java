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
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.Setting;

/** Factory for instantiating corpus implementations from profile configuration. */
public final class CorpusFactory {

  private CorpusFactory() {
  }

  public static AttributeModel attributeModel(HarnessConfig config, RandomStreams streams) {
    return new AttributeModel(streams, config.tenants(), config.tenantSkew(), config.sources(),
      config.categories(), config.aclTags(), config.filterSkew(), config.bodyBytes());
  }

  public static Corpus open(HarnessConfig config) throws IOException {
    RandomStreams streams = new RandomStreams(config.seed());
    AttributeModel attributes = attributeModel(config, streams);
    switch (config.corpusSource()) {
      case SYNTHETIC: {
        Metric metric = config.metric(null);
        return new SyntheticCorpus(config.seed(), config.rows(), config.dim(), config.clusters(),
          config.clusterZipf(), config.varianceSpread(), config.intrinsicDim(), config.noise(),
          config.normalize(metric), config.boundaryFraction(), config.oodFraction(),
          config.driftMode(), config.driftRate(), metric, attributes);
      }
      case TEXMEX:
        return checkHeldOut(config, TexmexCorpus.open(config.corpusPath(), config.corpusName(),
          config.rows(), config.holdoutRows(), streams, attributes));
      case HDF5:
        return checkHeldOut(config, Hdf5Corpus.open(config.corpusPath(), config.rows(),
          config.holdoutRows(), streams, attributes));
      default:
        throw new IllegalArgumentException("Unknown corpus source " + config.corpusSource());
    }
  }

  /**
   * Fails when the held-out rows of a published corpus cannot supply the planned new vectors. The
   * check runs before any table changes, so the run stops early with a clear message.
   */
  static PublishedCorpus checkHeldOut(HarnessConfig config, PublishedCorpus corpus) {
    long planned = config.plannedNewVectors();
    long held = corpus.heldOutCount();
    if (held < planned) {
      String message = corpus.description() + " holds out " + held
        + " rows, but the steady state can draw " + planned + " new vectors; raise "
        + Setting.CORPUS_HOLDOUT_ROWS.key() + " or lower corpus.rows";
      corpus.close();
      throw new IllegalArgumentException(message);
    }
    return corpus;
  }

  /** Resolves the effective distance metric between profile settings and corpus defaults. */
  public static Metric effectiveMetric(HarnessConfig config, Corpus corpus) {
    return config.metric(corpus.definedMetric());
  }
}
