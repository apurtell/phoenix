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

import java.nio.charset.StandardCharsets;

/**
 * Named pseudorandom streams derived from one master seed. Every value is a function of the master
 * seed, the stream name, and the ordinal of the element, so that any element can be generated or
 * regenerated alone, in any order, by any thread, with the same result. Stream names in use:
 * {@value #CLUSTERS} for the mixture components, {@value #DOCS} for document embeddings,
 * {@value #ATTRIBUTES} for relational attributes, {@value #QUERIES} for the query panel,
 * {@value #MUTATIONS} for the mutation schedule, and {@value #DRIFT} for geometric drift.
 */
public final class RandomStreams {

  public static final String CLUSTERS = "clusters";
  public static final String DOCS = "docs";
  public static final String ATTRIBUTES = "attributes";
  public static final String QUERIES = "queries";
  public static final String MUTATIONS = "mutations";
  public static final String DRIFT = "drift";

  private final long masterSeed;

  public RandomStreams(long masterSeed) {
    this.masterSeed = masterSeed;
  }

  public long masterSeed() {
    return masterSeed;
  }

  /** Returns the seed of a named stream. */
  public long streamSeed(String name) {
    return SplitMix64.combine(masterSeed, fnv1a64(name));
  }

  /** Returns a generator for one element of a named stream. */
  public SplitMix64 at(String stream, long ordinal) {
    return new SplitMix64(SplitMix64.combine(streamSeed(stream), ordinal));
  }

  /** Returns a generator for one element of a sub-stream, such as one cluster of a stream. */
  public SplitMix64 at(String stream, long sub, long ordinal) {
    return new SplitMix64(SplitMix64.combine(SplitMix64.combine(streamSeed(stream), sub), ordinal));
  }

  /** Returns a generator for an element of a stream whose seed the caller already derived. */
  public static SplitMix64 atSeed(long streamSeed, long ordinal) {
    return new SplitMix64(SplitMix64.combine(streamSeed, ordinal));
  }

  /** The 64-bit FNV-1a hash of the UTF-8 bytes of a string. */
  public static long fnv1a64(String s) {
    long h = 0xcbf29ce484222325L;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xff);
      h *= 0x100000001b3L;
    }
    return h;
  }
}
