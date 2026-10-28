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
 * Deterministic access to a vector corpus: base vectors and their attributes by row ordinal, an
 * evaluation query panel, the vectors that mutations write, and a fingerprint of all of it. Rows
 * are identified by ordinal in [0, rows()); rows that mutations insert get ordinals from rows()
 * upward. Implementations are thread safe.
 */
public interface Corpus extends Closeable {

  /** Returns a short description, such as synthetic:gmm or texmex:sift. */
  String description();

  /** Returns the source kind and name of the corpus, such as texmex:sift. */
  String sourceName();

  int dim();

  /** Returns the number of base rows. */
  long rows();

  /** Returns the metric that the corpus defines, or null if it defines none. */
  Metric definedMetric();

  /** Returns the base vector of a row ordinal in [0, rows()). Callers must not modify it. */
  float[] vector(long ordinal);

  /** Returns the mixture component of a base row, or -1 if the corpus has none. */
  int clusterOf(long ordinal);

  /** Returns the attributes of a row, for any ordinal. */
  DocAttributes attributes(long ordinal);

  AttributeModel attributeModel();

  /**
   * Returns the first {@code size} queries of the evaluation panel. A published corpus returns at
   * most the number of its published queries.
   */
  List<PanelQuery> panel(int size);

  /**
   * Returns the published neighbor ordinals of the panel queries, one row per query in panel order,
   * or null if the corpus publishes no ground truth.
   */
  int[][] publishedNeighbors();

  /** Returns the generator of the vectors that mutations write. */
  MutationVectors mutations();

  /** Returns the parameters that define the corpus, for fingerprints and manifests. */
  Map<String, String> parameters();

  /**
   * Returns the fingerprint of the corpus: a hex SHA-256 over the parameters that define the rows,
   * the attribute parameters, and the vector and attribute payloads. A synthetic corpus digests a
   * deterministic sample of its rows, since its rows are a function of its parameters, and leaves
   * out the panel and drift parameters, which do not change a row; a published corpus digests its
   * files, including a separate held-out file that mutations write, such as a TEXMEX learn file.
   * The first call may take a while; later calls return the cached value.
   */
  String fingerprint();

  /** Returns a hex SHA-256 of the first {@code size} panel queries. */
  String panelDigest(int size);

  @Override
  void close();
}
