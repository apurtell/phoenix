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
package org.apache.phoenix.vectorharness.truth;

import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;

/**
 * The rows that ground truth is computed over, scanned by ordinal ranges so that a brute force scan
 * can partition the work. Implementations must be safe for concurrent scans of disjoint ranges.
 */
public interface RowSource {

  /** Receives the rows of a scan. */
  interface RowVisitor {
    void visit(long ordinal, float[] vector, DocAttributes attributes);
  }

  /** Returns the exclusive upper bound of the ordinals of live rows. */
  long ordinalLimit();

  /** Visits every live row whose ordinal is in [from, to). */
  void scan(long from, long to, RowVisitor visitor);

  /** Returns a source of the base rows [0, rows) of a corpus. */
  static RowSource prefix(Corpus corpus, long rows) {
    final long n = Math.min(rows, corpus.rows());
    return new RowSource() {
      @Override
      public long ordinalLimit() {
        return n;
      }

      @Override
      public void scan(long from, long to, RowVisitor visitor) {
        long end = Math.min(to, n);
        for (long i = Math.max(0, from); i < end; i++) {
          visitor.visit(i, corpus.vector(i), corpus.attributes(i));
        }
      }
    };
  }
}
