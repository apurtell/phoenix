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
 * Generates the vectors that mutation workers write. Every method is a deterministic function of
 * its arguments. The minutes argument is the time since the start of the steady state, which the
 * drift model of a synthetic corpus uses to move, create, and retire clusters; published corpora
 * ignore it.
 */
public interface MutationVectors {

  /** Returns the vector of a row that a mutation inserts. */
  Embedding insert(long ordinal, double minutes);

  /**
   * Returns a local re-embedding of a row, which perturbs it within its current cluster.
   * @param cluster the current cluster of the row, or -1
   * @param current the current vector of the row
   * @param seq     the sequence number of the mutation
   */
  Embedding reembedLocal(long ordinal, int cluster, float[] current, long seq, double minutes);

  /** Returns a re-embedding that moves a row to a different cluster. */
  Embedding move(long ordinal, int cluster, long seq, double minutes);

  /**
   * Returns the probability that an update moves its row to another cluster, given the configured
   * move fraction. Drift by reassignment raises it over time.
   */
  double moveProbability(double baseFraction, double minutes);
}
