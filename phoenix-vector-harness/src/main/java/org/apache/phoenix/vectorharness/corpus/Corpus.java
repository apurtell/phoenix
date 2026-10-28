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

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.Metric;

/**
 * Interface providing deterministic access to vector datasets, relational attributes, query panels,
 * mutation vector generation, and corpus fingerprints. Thread safe.
 */
public interface Corpus extends Closeable {

  /** Returns a short description (e.g. {@code synthetic:gmm} or {@code texmex:sift}). */
  String description();

  /** Returns the corpus source name identifier (e.g. {@code texmex:sift}). */
  String sourceName();

  int dim();

  /** Returns total count of base rows. */
  long rows();

  /** Returns the metric defined by the dataset, or null if none is specified. */
  Metric definedMetric();

  /**
   * Returns the base vector for a row ordinal in {@code [0, rows())}.
   * <p>
   * Callers must not modify it.
   */
  float[] vector(long ordinal);

  /** Returns the mixture component or cluster ID of a row, or -1 if unclustered. */
  int clusterOf(long ordinal);

  /** Returns the document attributes for the given row ordinal. */
  DocAttributes attributes(long ordinal);

  AttributeModel attributeModel();

  /** Returns the first {@code size} queries of the evaluation panel. */
  List<PanelQuery> panel(int size);

  /**
   * Returns published ground truth neighbor ordinals for panel queries, or null if unavailable.
   */
  int[][] publishedNeighbors();

  /** Returns the generator for mutation insert and move vectors. */
  MutationVectors mutations();

  /** Returns corpus parameters for manifest generation and fingerprinting. */
  Map<String, String> parameters();

  /**
   * Returns the SHA-256 hex digest fingerprint uniquely identifying the corpus data and
   * configuration.
   */
  String fingerprint();

  /** Returns the SHA-256 hex digest of the first {@code size} panel queries. */
  String panelDigest(int size);

  @Override
  void close();
}
