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

/** Opens the corpus that a profile describes. */
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
        return TexmexCorpus.open(config.corpusPath(), config.corpusName(), config.rows(), streams,
          attributes);
      case HDF5:
        return Hdf5Corpus.open(config.corpusPath(), config.rows(), streams, attributes);
      default:
        throw new IllegalArgumentException("Unknown corpus source " + config.corpusSource());
    }
  }

  /** Returns the metric of a run over a corpus: the profile metric, else the corpus metric. */
  public static Metric effectiveMetric(HarnessConfig config, Corpus corpus) {
    return config.metric(corpus.definedMetric());
  }
}
