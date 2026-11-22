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
package org.apache.phoenix.pherf.workload.cogen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.phoenix.pherf.configuration.CoGeneration.WatermarkPolicy;

/**
 * Tracks the local row index space for a single scenario within one process by maintaining both the
 * claimed high-water mark and the committed watermark. Writers claim disjoint index blocks from a
 * shared atomic counter such that no block ever crosses a multiple of the configured block size.
 * Every claimed block is written, ensuring that no gaps remain below the high-water mark.
 * <p>
 * The watermark marks the end of the contiguous committed prefix. Any block that commits above an
 * uncommitted gap is buffered in a pending map until the intervening gap commits. Consequently, the
 * watermark advances monotonically, and every local index below it is guaranteed to be committed.
 * <p>
 * The delete watermark marks the end of the contiguous deleted prefix. Delete operations claim
 * aligned windows of the configured block size sequentially, and a window can be claimed only after
 * all of its rows have been committed. Thus, the set of live rows always spans the interval
 * [deleted, watermark), and readers draw exclusively from that range.
 */
public final class RowCursor {
  /**
   * Represents the half-open interval of local row indexes [start, end) assigned to a single writer
   * block.
   */
  public static final class Block {
    public final long start;
    public final long end;

    public Block(long start, long end) {
      this.start = start;
      this.end = end;
    }

    public long size() {
      return end - start;
    }
  }

  private final int blockSize;
  private final AtomicLong next = new AtomicLong();
  private final AtomicLong deleted = new AtomicLong();
  // Stores committed index ranges above the current watermark, keyed by start index. All accesses
  // must synchronize on this map instance.
  private final Map<Long, Long> pending = new TreeMap<>();
  private volatile long watermark;

  public RowCursor(int blockSize, long start) {
    this.blockSize = blockSize;
    reset(start);
  }

  /**
   * Atomically claims the next contiguous block of at most maxRows local indexes, truncating the
   * block so that it never extends past the next multiple of the block size.
   */
  public Block claim(long maxRows) {
    while (true) {
      long s = next.get();
      long e = Math.min(s + maxRows, (s / blockSize + 1) * blockSize);
      if (next.compareAndSet(s, e)) {
        return new Block(s, e);
      }
    }
  }

  /**
   * Records a newly committed block and advances the watermark across any newly contiguous
   * committed prefix.
   */
  public void commit(Block block) {
    synchronized (pending) {
      pending.put(block.start, block.end);
      Long end;
      long mark = watermark;
      while ((end = pending.remove(mark)) != null) {
        mark = end;
      }
      watermark = mark;
    }
  }

  /**
   * Returns the contiguous committed watermark, representing the number of committed local indexes
   * such that every index below this value has been committed.
   */
  public long watermark() {
    return watermark;
  }

  /**
   * Returns the delete watermark, representing the number of deleted local indexes such that every
   * index below this value has been deleted or claimed for deletion.
   */
  public long deleted() {
    return deleted.get();
  }

  /**
   * Claims the next aligned window of the block size for deletion, or the remaining rows for the
   * final partial window. Claiming a window advances the delete watermark before the delete
   * operation executes, ensuring that no subsequent read or scan draw returns a row from that
   * window.
   * @param rows the total number of rows configured for the scenario
   * @return the claimed window, or null if the next window is not yet fully committed or no windows
   *         remain
   */
  public Block claimDelete(long rows) {
    while (true) {
      long d = deleted.get();
      long e = Math.min(d + blockSize, rows);
      if (e <= d || e > watermark) {
        return null;
      }
      if (deleted.compareAndSet(d, e)) {
        return new Block(d, e);
      }
    }
  }

  /**
   * Returns the number of complete block-sized windows below the committed watermark available for
   * scan operations.
   */
  public long windows(long rows) {
    long mark = watermark;
    return mark >= rows ? (rows + blockSize - 1) / blockSize : mark / blockSize;
  }

  /** Returns the block of row indexes corresponding to window k for a scan operation. */
  public Block window(long k, long rows) {
    return new Block(k * blockSize, Math.min((k + 1) * blockSize, rows));
  }

  /** Returns the number of windows covered by the current delete watermark. */
  public long deletedWindows() {
    return (deleted.get() + blockSize - 1) / blockSize;
  }

  /** Returns the high-water mark, representing the total number of claimed local indexes. */
  public long highWater() {
    return next.get();
  }

  /**
   * Resets both the high-water mark and the committed watermark to start, and resets the delete
   * watermark to 0.
   */
  public void reset(long start) {
    synchronized (pending) {
      pending.clear();
      next.set(start);
      watermark = start;
      deleted.set(0);
    }
  }

  /**
   * Creates a workload session that tracks blocks claimed during a single write workload and
   * publishes their commits according to the specified watermark policy.
   */
  public Load load(WatermarkPolicy policy) {
    return new Load(policy);
  }

  /** Manages the blocks claimed and committed within a single write workload. */
  public final class Load {
    private final WatermarkPolicy policy;
    private final List<Block> held = new ArrayList<>();

    private Load(WatermarkPolicy policy) {
      this.policy = policy;
    }

    public Block claim(long maxRows) {
      return RowCursor.this.claim(maxRows);
    }

    /**
     * Invoked after the JDBC transaction for a block has committed. Under the COMMIT policy the
     * block is published to the watermark immediately, whereas under the LOAD policy it is held
     * until finish is called.
     */
    public void committed(Block block) {
      if (policy == WatermarkPolicy.LOAD) {
        synchronized (held) {
          held.add(block);
        }
      } else {
        commit(block);
      }
    }

    /**
     * Invoked after all writers in the write workload complete to publish any blocks deferred under
     * the LOAD policy.
     */
    public void finish() {
      synchronized (held) {
        for (Block block : held) {
          commit(block);
        }
        held.clear();
      }
    }
  }
}
