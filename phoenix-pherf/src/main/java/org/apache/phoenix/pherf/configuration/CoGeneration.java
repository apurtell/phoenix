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
package org.apache.phoenix.pherf.configuration;

import javax.xml.bind.annotation.XmlAttribute;

/**
 * Configuration model for deterministic co-generation within a scenario. When a scenario includes
 * this element, every column value is computed as a pure function of the row index, the column
 * rule, and the seed. Writer threads claim disjoint blocks of row indexes while reader threads
 * sample exclusively from committed index ranges, allowing readers to reconstruct the exact keys
 * produced by writers without sharing state. When this element is omitted, the traditional
 * rule-based generators execute as before.
 * <p>
 * Each attribute stores its raw XML text representation, leaving omitted attributes as null. The
 * co-generator settings parser validates and converts these text values explicitly so that any
 * malformed value fails fast with an error message identifying the scenario, the attribute, and the
 * offending value rather than silently falling back to a default. The default attribute values are:
 * <ul>
 * <li>seed 0: defines the seed used for all column value generation and for the reader walk.</li>
 * <li>offset 0: specifies the starting row index for the current process, which must be 0 or
 * greater. Two processes generate disjoint key sets only when their offsets are separated by at
 * least the total number of rows written by a process (including rows produced by writeParams
 * writers) and the primary key contains a column that yields distinct values for every row index
 * across both ranges.</li>
 * <li>blockSize 1000: specifies the number of row indexes allocated per writer block, which must be
 * 1 or greater. Each block corresponds to a single commit batch and never crosses an integer
 * multiple of blockSize.</li>
 * <li>clusterLimit absent: disables clustering by default. Setting a value of 1 or greater caps the
 * number of distinct values generated for the leading primary key column within each block. A value
 * smaller than blockSize requires a composite primary key containing at least two columns.</li>
 * <li>watermark COMMIT: exposes each block to readers immediately after it is committed, whereas
 * LOAD exposes blocks from a write workload only after all of its writers have finished.</li>
 * <li>walk MULTIPLICATIVE: directs readers to traverse committed rows in a hashed order, whereas
 * SEQUENTIAL traverses committed rows in ascending index order.</li>
 * </ul>
 */
public class CoGeneration {
  /** Defines the visibility policy governing when rows in a writer block become readable. */
  public enum WatermarkPolicy {
    /** Exposes the rows of a block to readers as soon as that block is committed. */
    COMMIT,
    /** Exposes rows to readers only after all writers in the write workload have completed. */
    LOAD
  }

  /** Defines the traversal order in which a reader visits committed rows. */
  public enum WalkStrategy {
    /**
     * Traverses committed rows from a hashed starting offset using a step coprime to the window.
     */
    MULTIPLICATIVE,
    /** Traverses committed rows sequentially in index order. */
    SEQUENTIAL
  }

  private String seed;
  private String offset;
  private String blockSize;
  private String clusterLimit;
  private String watermark;
  private String walk;

  @XmlAttribute
  public String getSeed() {
    return seed;
  }

  public void setSeed(String seed) {
    this.seed = seed;
  }

  @XmlAttribute
  public String getOffset() {
    return offset;
  }

  public void setOffset(String offset) {
    this.offset = offset;
  }

  @XmlAttribute
  public String getBlockSize() {
    return blockSize;
  }

  public void setBlockSize(String blockSize) {
    this.blockSize = blockSize;
  }

  @XmlAttribute
  public String getClusterLimit() {
    return clusterLimit;
  }

  public void setClusterLimit(String clusterLimit) {
    this.clusterLimit = clusterLimit;
  }

  @XmlAttribute
  public String getWatermark() {
    return watermark;
  }

  public void setWatermark(String watermark) {
    this.watermark = watermark;
  }

  @XmlAttribute
  public String getWalk() {
    return walk;
  }

  public void setWalk(String walk) {
    this.walk = walk;
  }
}
