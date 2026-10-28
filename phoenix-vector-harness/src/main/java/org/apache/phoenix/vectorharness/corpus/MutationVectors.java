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

/**
 * Generates vectors for mutation operations (inserts, local perturbations, and cluster migrations)
 * deterministically as a function of ordinal, sequence number, and elapsed steady state duration.
 */
public interface MutationVectors {

  /** Generates an embedding for an inserted row. */
  Embedding insert(long ordinal, double minutes);

  /**
   * Generates a local perturbation of a row's vector within its current cluster.
   * @param cluster current cluster of the row, or -1
   * @param current current vector of the row
   * @param seq     mutation sequence number
   * @param minutes elapsed steady-state duration in minutes
   */
  Embedding reembedLocal(long ordinal, int cluster, float[] current, long seq, double minutes);

  /** Generates a new vector migrating the row to a different cluster. */
  Embedding move(long ordinal, int cluster, long seq, double minutes);

  /**
   * Returns the probability that an update migrates a row to another cluster, accounting for drift.
   */
  double moveProbability(double baseFraction, double minutes);
}
