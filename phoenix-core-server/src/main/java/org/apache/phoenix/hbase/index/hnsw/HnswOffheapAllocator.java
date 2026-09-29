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
package org.apache.phoenix.hbase.index.hnsw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Centralized off-heap memory allocator and budget manager shared across all
 * {@link org.apache.phoenix.hbase.index.vector.HnswIndexManager} instances on a RegionServer.
 * <p>
 * The allocator enforces a bounded memory limit configured by
 * {@value org.apache.phoenix.query.QueryServices#HNSW_OFFHEAP_MAX_BYTES_ATTRIB} (default 2 GB).
 * When an allocation request exceeds the remaining off-heap budget, the allocator evicts the least
 * recently queried immutable graph segments in LRU order, releasing their {@link ByteBuffer} direct
 * buffers and invoking registered eviction callbacks. Evicted segments are subsequently
 * rematerialized on demand.
 */
public class HnswOffheapAllocator implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(HnswOffheapAllocator.class);

  public static final String HNSW_OFFHEAP_MAX_BYTES_ATTRIB =
    QueryServices.HNSW_OFFHEAP_MAX_BYTES_ATTRIB;
  public static final long DEFAULT_HNSW_OFFHEAP_MAX_BYTES =
    QueryServicesOptions.DEFAULT_HNSW_OFFHEAP_MAX_BYTES;

  private static volatile HnswOffheapAllocator defaultInstance;
  private static final Object INIT_LOCK = new Object();

  private final long maxBytes;
  private final AtomicLong allocatedBytes = new AtomicLong(0);
  private final AtomicLong evictionCount = new AtomicLong(0);
  private final AtomicLong allocationCount = new AtomicLong(0);

  private final Object lock = new Object();
  private final LinkedHashMap<SegmentKey, SegmentAllocation> lruMap =
    new LinkedHashMap<>(16, 0.75f, true);
  private volatile boolean closed = false;

  /** Key uniquely identifying an immutable graph segment across tables and regions. */
  public static class SegmentKey {
    private final String tableName;
    private final byte[] regionName;
    private final byte[] segmentRowKey;

    public SegmentKey(String tableName, byte[] regionName, byte[] segmentRowKey) {
      this.tableName = tableName != null ? tableName : "";
      this.regionName =
        regionName != null ? Arrays.copyOf(regionName, regionName.length) : new byte[0];
      this.segmentRowKey =
        segmentRowKey != null ? Arrays.copyOf(segmentRowKey, segmentRowKey.length) : new byte[0];
    }

    public static SegmentKey of(String tableName, byte[] regionName, byte[] segmentRowKey) {
      return new SegmentKey(tableName, regionName, segmentRowKey);
    }

    public static SegmentKey of(String identifier) {
      return new SegmentKey(identifier, new byte[0], new byte[0]);
    }

    public String getTableName() {
      return tableName;
    }

    public byte[] getRegionName() {
      return Arrays.copyOf(regionName, regionName.length);
    }

    public byte[] getSegmentRowKey() {
      return Arrays.copyOf(segmentRowKey, segmentRowKey.length);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SegmentKey that = (SegmentKey) o;
      return Objects.equals(tableName, that.tableName) && Arrays.equals(regionName, that.regionName)
        && Arrays.equals(segmentRowKey, that.segmentRowKey);
    }

    @Override
    public int hashCode() {
      int result = Objects.hashCode(tableName);
      result = 31 * result + Arrays.hashCode(regionName);
      result = 31 * result + Arrays.hashCode(segmentRowKey);
      return result;
    }

    @Override
    public String toString() {
      return tableName + "/" + Bytes.toStringBinary(regionName) + "/"
        + Bytes.toStringBinary(segmentRowKey);
    }
  }

  /** Callback invoked when a segment is evicted from off-heap memory. */
  @FunctionalInterface
  public interface EvictionListener {
    void onEvict(SegmentKey key);
  }

  /** Internal representation of a tracked segment allocation in off-heap memory. */
  private static class SegmentAllocation {
    private final SegmentKey key;
    private final long size;
    private final ByteBuffer buffer;
    private volatile EvictionListener listener;
    private volatile long lastAccessTime;

    public SegmentAllocation(SegmentKey key, long size, ByteBuffer buffer,
      EvictionListener listener) {
      this.key = key;
      this.size = size;
      this.buffer = buffer;
      this.listener = listener;
      this.lastAccessTime = System.currentTimeMillis();
    }

    public SegmentKey getKey() {
      return key;
    }

    public long getSize() {
      return size;
    }

    public ByteBuffer getBuffer() {
      return buffer;
    }

    public EvictionListener getListener() {
      return listener;
    }

    public void setListener(EvictionListener listener) {
      this.listener = listener;
    }

    public long getLastAccessTime() {
      return lastAccessTime;
    }

    public void touch() {
      this.lastAccessTime = System.currentTimeMillis();
    }
  }

  /**
   * Returns the singleton allocator instance for the RegionServer, configuring the off-heap limit
   * from {@value org.apache.phoenix.query.QueryServices#HNSW_OFFHEAP_MAX_BYTES_ATTRIB}.
   */
  public static HnswOffheapAllocator getInstance(Configuration conf) {
    if (defaultInstance == null) {
      synchronized (INIT_LOCK) {
        if (defaultInstance == null) {
          long maxBytes = conf != null
            ? conf.getLong(HNSW_OFFHEAP_MAX_BYTES_ATTRIB, DEFAULT_HNSW_OFFHEAP_MAX_BYTES)
            : DEFAULT_HNSW_OFFHEAP_MAX_BYTES;
          defaultInstance = new HnswOffheapAllocator(maxBytes);
        }
      }
    }
    return defaultInstance;
  }

  /**
   * Returns the singleton allocator instance with default configuration.
   */
  public static HnswOffheapAllocator getInstance() {
    return getInstance(null);
  }

  /**
   * Resets the singleton instance (primarily for testing).
   */
  public static void resetInstance() {
    synchronized (INIT_LOCK) {
      if (defaultInstance != null) {
        defaultInstance.close();
        defaultInstance = null;
      }
    }
  }

  /**
   * Constructs an allocator with an explicit maximum byte budget.
   * @param maxBytes the maximum off-heap budget in bytes
   */
  public HnswOffheapAllocator(long maxBytes) {
    Preconditions.checkArgument(maxBytes > 0, "maxBytes must be positive: %s", maxBytes);
    this.maxBytes = maxBytes;
  }

  /**
   * Constructs an allocator configured from the provided {@link Configuration}.
   */
  public HnswOffheapAllocator(Configuration conf) {
    this(conf != null
      ? conf.getLong(HNSW_OFFHEAP_MAX_BYTES_ATTRIB, DEFAULT_HNSW_OFFHEAP_MAX_BYTES)
      : DEFAULT_HNSW_OFFHEAP_MAX_BYTES);
  }

  /**
   * Allocates an off-heap direct {@link ByteBuffer} of the specified size for the given segment. If
   * the allocation exceeds the available budget, least recently queried segments are evicted to
   * satisfy the request.
   * @param key       the segment key
   * @param sizeBytes the allocation size in bytes
   * @param listener  callback notified if this segment is subsequently evicted
   * @return a direct {@link ByteBuffer} of capacity {@code sizeBytes}
   * @throws IOException if the allocation size exceeds the maximum budget or insufficient memory
   *                     remains after evicting all eligible segments
   */
  public ByteBuffer allocate(SegmentKey key, int sizeBytes, EvictionListener listener)
    throws IOException {
    Preconditions.checkNotNull(key, "key cannot be null");
    Preconditions.checkArgument(sizeBytes >= 0, "sizeBytes must be non-negative: %s", sizeBytes);
    checkNotClosed();

    if (sizeBytes > maxBytes) {
      throw new IOException(String.format(
        "Requested allocation of %d bytes exceeds maximum off-heap budget of %d bytes", sizeBytes,
        maxBytes));
    }

    List<SegmentAllocation> evictedAllocations = new ArrayList<>();
    ByteBuffer buffer;

    synchronized (lock) {
      checkNotClosed();

      // If key is already tracked, release prior allocation
      SegmentAllocation existing = lruMap.remove(key);
      if (existing != null) {
        allocatedBytes.addAndGet(-existing.getSize());
      }

      // Evict least-recently-queried segments until budget is sufficient
      while (allocatedBytes.get() + sizeBytes > maxBytes && !lruMap.isEmpty()) {
        Map.Entry<SegmentKey, SegmentAllocation> eldest = lruMap.entrySet().iterator().next();
        lruMap.remove(eldest.getKey());
        allocatedBytes.addAndGet(-eldest.getValue().getSize());
        evictionCount.incrementAndGet();
        evictedAllocations.add(eldest.getValue());
        LOG.info(
          "Evicting HNSW segment {} (size={} bytes) to fit allocation request of {} bytes; "
            + "allocated={} bytes, max={} bytes",
          eldest.getKey(), eldest.getValue().getSize(), sizeBytes, allocatedBytes.get(), maxBytes);
      }

      if (allocatedBytes.get() + sizeBytes > maxBytes) {
        throw new IOException(String.format(
          "Insufficient off-heap memory budget: requested %d bytes, available %d bytes (max=%d)",
          sizeBytes, maxBytes - allocatedBytes.get(), maxBytes));
      }

      buffer = ByteBuffer.allocateDirect(sizeBytes);
      SegmentAllocation allocation = new SegmentAllocation(key, sizeBytes, buffer, listener);
      lruMap.put(key, allocation);
      allocatedBytes.addAndGet(sizeBytes);
      allocationCount.incrementAndGet();
    }

    // Invoke eviction listeners outside the lock to prevent deadlock
    notifyEvictions(evictedAllocations);

    return buffer;
  }

  /** Convenience overload for String segment identifiers. */
  public ByteBuffer allocate(String segmentId, int sizeBytes, EvictionListener listener)
    throws IOException {
    return allocate(SegmentKey.of(segmentId), sizeBytes, listener);
  }

  /**
   * Allocates an off-heap direct {@link ByteBuffer} and copies the byte payload into it.
   */
  public ByteBuffer allocate(SegmentKey key, byte[] payload, EvictionListener listener)
    throws IOException {
    Preconditions.checkNotNull(payload, "payload cannot be null");
    ByteBuffer buffer = allocate(key, payload.length, listener);
    buffer.put(payload);
    buffer.flip();
    return buffer;
  }

  /**
   * Allocates an off-heap direct {@link ByteBuffer} and copies the resolved MOB cell payload into
   * it. Returns null if cell is null.
   */
  public ByteBuffer allocate(SegmentKey key, Cell cell, EvictionListener listener)
    throws IOException {
    if (cell == null) {
      return null;
    }
    int length = cell.getValueLength();
    ByteBuffer buffer = allocate(key, length, listener);
    buffer.put(cell.getValueArray(), cell.getValueOffset(), length);
    buffer.flip();
    return buffer;
  }

  /**
   * Registers an existing direct {@link ByteBuffer} under the given segment key, accounting for its
   * capacity against the off-heap budget and evicting LRU segments if necessary.
   */
  public void register(SegmentKey key, ByteBuffer directBuffer, EvictionListener listener)
    throws IOException {
    Preconditions.checkNotNull(key, "key cannot be null");
    if (directBuffer == null) {
      return;
    }
    int sizeBytes = directBuffer.capacity();
    checkNotClosed();

    List<SegmentAllocation> evictedAllocations = new ArrayList<>();
    synchronized (lock) {
      checkNotClosed();
      SegmentAllocation existing = lruMap.get(key);
      if (existing != null && existing.getBuffer() == directBuffer) {
        existing.setListener(listener);
        existing.touch();
        return;
      }
      if (existing != null) {
        lruMap.remove(key);
        allocatedBytes.addAndGet(-existing.getSize());
      }
      while (allocatedBytes.get() + sizeBytes > maxBytes && !lruMap.isEmpty()) {
        Map.Entry<SegmentKey, SegmentAllocation> eldest = lruMap.entrySet().iterator().next();
        lruMap.remove(eldest.getKey());
        allocatedBytes.addAndGet(-eldest.getValue().getSize());
        evictionCount.incrementAndGet();
        evictedAllocations.add(eldest.getValue());
      }
      if (allocatedBytes.get() + sizeBytes > maxBytes) {
        throw new IOException(String.format(
          "Insufficient off-heap memory budget to register segment: requested %d bytes, available %d bytes (max=%d)",
          sizeBytes, maxBytes - allocatedBytes.get(), maxBytes));
      }
      SegmentAllocation allocation = new SegmentAllocation(key, sizeBytes, directBuffer, listener);
      lruMap.put(key, allocation);
      allocatedBytes.addAndGet(sizeBytes);
      allocationCount.incrementAndGet();
    }
    notifyEvictions(evictedAllocations);
  }

  /**
   * Releases the segment allocation associated with the key, freeing its budgeted bytes.
   */
  public void release(SegmentKey key) {
    if (key == null) {
      return;
    }
    synchronized (lock) {
      SegmentAllocation alloc = lruMap.remove(key);
      if (alloc != null) {
        allocatedBytes.addAndGet(-alloc.getSize());
        LOG.debug("Released segment {} (size={} bytes); remaining allocated={} bytes", key,
          alloc.getSize(), allocatedBytes.get());
      }
    }
  }

  /** Convenience overload for String segment identifiers. */
  public void release(String segmentId) {
    release(SegmentKey.of(segmentId));
  }

  /**
   * Records query access to a segment, moving it to the most-recently-used position in the LRU
   * eviction queue.
   */
  public void recordAccess(SegmentKey key) {
    if (key == null) {
      return;
    }
    synchronized (lock) {
      SegmentAllocation alloc = lruMap.get(key);
      if (alloc != null) {
        alloc.touch();
      }
    }
  }

  /** Convenience overload for String segment identifiers. */
  public void recordAccess(String segmentId) {
    recordAccess(SegmentKey.of(segmentId));
  }

  /** Returns the configured maximum off-heap budget in bytes. */
  public long getMaxBytes() {
    return maxBytes;
  }

  /** Returns the total currently allocated off-heap bytes. */
  public long getAllocatedBytes() {
    return allocatedBytes.get();
  }

  /** Returns the remaining unallocated off-heap bytes before eviction is triggered. */
  public long getAvailableBytes() {
    return Math.max(0, maxBytes - allocatedBytes.get());
  }

  /** Returns the number of segments currently held in off-heap memory. */
  public int getTrackedSegmentCount() {
    synchronized (lock) {
      return lruMap.size();
    }
  }

  /** Returns the cumulative count of segment evictions performed. */
  public long getEvictionCount() {
    return evictionCount.get();
  }

  /** Returns the cumulative count of segment allocations performed. */
  public long getAllocationCount() {
    return allocationCount.get();
  }

  /** Returns true if the segment is currently resident in off-heap memory. */
  public boolean isTracked(SegmentKey key) {
    if (key == null) {
      return false;
    }
    synchronized (lock) {
      return lruMap.containsKey(key);
    }
  }

  /** Returns the direct buffer backing the segment, or null if not resident. */
  public ByteBuffer getBuffer(SegmentKey key) {
    if (key == null) {
      return null;
    }
    synchronized (lock) {
      SegmentAllocation alloc = lruMap.get(key);
      return alloc != null ? alloc.getBuffer() : null;
    }
  }

  /**
   * Releases all tracked segment allocations and clears the allocator.
   */
  public void clear() {
    List<SegmentAllocation> evicted = new ArrayList<>();
    synchronized (lock) {
      evicted.addAll(lruMap.values());
      lruMap.clear();
      allocatedBytes.set(0);
    }
    notifyEvictions(evicted);
  }

  @Override
  public void close() {
    clear();
    this.closed = true;
  }

  private void notifyEvictions(List<SegmentAllocation> evictedAllocations) {
    if (evictedAllocations == null || evictedAllocations.isEmpty()) {
      return;
    }
    for (SegmentAllocation alloc : evictedAllocations) {
      if (alloc.getListener() != null) {
        try {
          alloc.getListener().onEvict(alloc.getKey());
        } catch (Throwable t) {
          LOG.warn("Error invoking eviction listener for segment {}", alloc.getKey(), t);
        }
      }
    }
  }

  private void checkNotClosed() {
    if (closed) {
      throw new IllegalStateException("HnswOffheapAllocator is closed");
    }
  }
}
