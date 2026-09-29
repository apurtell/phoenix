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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator.SegmentKey;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link HnswOffheapAllocator}: memory budgeting, LRU segment eviction, access-order
 * touch, thread safety, and lifecycle management.
 */
public class HnswOffheapAllocatorTest {

  @After
  public void tearDown() {
    HnswOffheapAllocator.resetInstance();
  }

  @Test
  public void testBudgetTrackingAndBasicAllocation() throws Exception {
    long maxBudget = 1024L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      assertEquals(maxBudget, allocator.getMaxBytes());
      assertEquals(0L, allocator.getAllocatedBytes());
      assertEquals(maxBudget, allocator.getAvailableBytes());
      assertEquals(0, allocator.getTrackedSegmentCount());
      assertEquals(0L, allocator.getEvictionCount());
      assertEquals(0L, allocator.getAllocationCount());

      SegmentKey key1 = SegmentKey.of("TBL1", Bytes.toBytes("r1"), Bytes.toBytes("seg1"));
      ByteBuffer buf1 = allocator.allocate(key1, 400, null);

      assertNotNull(buf1);
      assertTrue("Buffer must be direct", buf1.isDirect());
      assertEquals(400, buf1.capacity());
      assertEquals(400L, allocator.getAllocatedBytes());
      assertEquals(624L, allocator.getAvailableBytes());
      assertEquals(1, allocator.getTrackedSegmentCount());
      assertEquals(1L, allocator.getAllocationCount());
      assertTrue(allocator.isTracked(key1));
      assertEquals(buf1, allocator.getBuffer(key1));
    }
  }

  @Test
  public void testAllocationExceedingMaxBudgetFails() {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      SegmentKey key = SegmentKey.of("seg_huge");
      try {
        allocator.allocate(key, 1001, null);
        fail("Should have failed with IOException when allocation exceeds maximum budget");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("exceeds maximum off-heap budget"));
      }
      assertEquals(0L, allocator.getAllocatedBytes());
      assertEquals(maxBudget, allocator.getAvailableBytes());
      assertEquals(0, allocator.getTrackedSegmentCount());
    }
  }

  @Test
  public void testLruEvictionWhenBudgetExceeded() throws Exception {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      SegmentKey key1 = SegmentKey.of("TBL", Bytes.toBytes("r1"), Bytes.toBytes("s1"));
      SegmentKey key2 = SegmentKey.of("TBL", Bytes.toBytes("r1"), Bytes.toBytes("s2"));
      SegmentKey key3 = SegmentKey.of("TBL", Bytes.toBytes("r1"), Bytes.toBytes("s3"));

      AtomicBoolean key1Evicted = new AtomicBoolean(false);
      AtomicBoolean key2Evicted = new AtomicBoolean(false);

      allocator.allocate(key1, 400, k -> key1Evicted.set(true));
      allocator.allocate(key2, 400, k -> key2Evicted.set(true));

      assertEquals(800L, allocator.getAllocatedBytes());
      assertEquals(200L, allocator.getAvailableBytes());
      assertEquals(2, allocator.getTrackedSegmentCount());
      assertFalse(key1Evicted.get());
      assertFalse(key2Evicted.get());

      // Allocate key3 requiring 300 bytes (800 + 300 = 1100 > 1000)
      // Least recently used is key1, so key1 should be evicted
      allocator.allocate(key3, 300, null);

      assertTrue("key1 should have been evicted", key1Evicted.get());
      assertFalse("key2 should not have been evicted", key2Evicted.get());
      assertFalse("key1 is no longer tracked", allocator.isTracked(key1));
      assertTrue("key2 remains tracked", allocator.isTracked(key2));
      assertTrue("key3 is tracked", allocator.isTracked(key3));

      // Remaining bytes: 400 (key2) + 300 (key3) = 700 bytes
      assertEquals(700L, allocator.getAllocatedBytes());
      assertEquals(300L, allocator.getAvailableBytes());
      assertEquals(2, allocator.getTrackedSegmentCount());
      assertEquals(1L, allocator.getEvictionCount());
      assertEquals(3L, allocator.getAllocationCount());
    }
  }

  @Test
  public void testLruAccessOrderPreservation() throws Exception {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      SegmentKey keyA = SegmentKey.of("segA");
      SegmentKey keyB = SegmentKey.of("segB");
      SegmentKey keyC = SegmentKey.of("segC");

      AtomicBoolean keyAEvicted = new AtomicBoolean(false);
      AtomicBoolean keyBEvicted = new AtomicBoolean(false);

      allocator.allocate(keyA, 400, k -> keyAEvicted.set(true));
      allocator.allocate(keyB, 400, k -> keyBEvicted.set(true));

      // Record access on keyA (simulates query on segment A)
      allocator.recordAccess(keyA);

      // Now keyB is the least recently accessed segment.
      // Allocating keyC (400 bytes) requires evicting one segment (800 + 400 = 1200 > 1000).
      // keyB should be evicted, not keyA!
      allocator.allocate(keyC, 400, null);

      assertFalse("keyA was recently accessed, should NOT be evicted", keyAEvicted.get());
      assertTrue("keyB was least recently accessed, MUST be evicted", keyBEvicted.get());

      assertTrue(allocator.isTracked(keyA));
      assertFalse(allocator.isTracked(keyB));
      assertTrue(allocator.isTracked(keyC));
      assertEquals(800L, allocator.getAllocatedBytes());
    }
  }

  @Test
  public void testManualReleaseFreesBudget() throws Exception {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      SegmentKey key1 = SegmentKey.of("seg1");
      allocator.allocate(key1, 600, null);
      assertEquals(600L, allocator.getAllocatedBytes());

      allocator.release(key1);
      assertEquals(0L, allocator.getAllocatedBytes());
      assertEquals(maxBudget, allocator.getAvailableBytes());
      assertFalse(allocator.isTracked(key1));

      // Allocate larger segment; succeeds without any evictions
      SegmentKey key2 = SegmentKey.of("seg2");
      allocator.allocate(key2, 800, null);
      assertEquals(800L, allocator.getAllocatedBytes());
      assertEquals(0L, allocator.getEvictionCount());
    }
  }

  @Test
  public void testPayloadAndCellAllocation() throws Exception {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      byte[] payload = Bytes.toBytes("graph_segment_bytes_12345");
      SegmentKey key1 = SegmentKey.of("seg_payload");
      ByteBuffer buf1 = allocator.allocate(key1, payload, null);

      assertNotNull(buf1);
      assertTrue(buf1.isDirect());
      assertEquals(payload.length, buf1.remaining());
      byte[] readBytes = new byte[buf1.remaining()];
      buf1.get(readBytes);
      assertArrayEquals(payload, readBytes);

      // Cell allocation
      byte[] rowKey = Bytes.toBytes("row_mob");
      byte[] fam = Bytes.toBytes("0");
      byte[] qual = Bytes.toBytes("_G");
      KeyValue kv = new KeyValue(rowKey, fam, qual, payload);

      SegmentKey key2 = SegmentKey.of("seg_cell");
      ByteBuffer buf2 = allocator.allocate(key2, kv, null);
      assertNotNull(buf2);
      assertTrue(buf2.isDirect());
      assertEquals(payload.length, buf2.remaining());
      byte[] cellRead = new byte[buf2.remaining()];
      buf2.get(cellRead);
      assertArrayEquals(payload, cellRead);

      // Null cell
      assertNull(
        allocator.allocate(SegmentKey.of("null_cell"), (org.apache.hadoop.hbase.Cell) null, null));
    }
  }

  @Test
  public void testRegisterExistingDirectBuffer() throws Exception {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      ByteBuffer directBuffer = ByteBuffer.allocateDirect(500);
      SegmentKey key = SegmentKey.of("direct_seg");

      allocator.register(key, directBuffer, null);
      assertEquals(500L, allocator.getAllocatedBytes());
      assertTrue(allocator.isTracked(key));

      // Re-registering same buffer is idempotent
      allocator.register(key, directBuffer, null);
      assertEquals(500L, allocator.getAllocatedBytes());
    }
  }

  @Test
  public void testReallocationForSameKeyReplacesOld() throws Exception {
    long maxBudget = 1000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      SegmentKey key = SegmentKey.of("TBL", Bytes.toBytes("r1"), Bytes.toBytes("seg1"));
      allocator.allocate(key, 400, null);
      assertEquals(400L, allocator.getAllocatedBytes());

      // Re-allocate same key with larger buffer (cutover)
      allocator.allocate(key, 500, null);
      assertEquals(500L, allocator.getAllocatedBytes());
      assertEquals(0L, allocator.getEvictionCount());
      assertEquals(1, allocator.getTrackedSegmentCount());
    }
  }

  @Test
  public void testSingletonLifecycle() {
    HnswOffheapAllocator.resetInstance();
    HnswOffheapAllocator allocator1 = HnswOffheapAllocator.getInstance();
    assertNotNull(allocator1);
    assertEquals(QueryServicesOptions.DEFAULT_HNSW_OFFHEAP_MAX_BYTES, allocator1.getMaxBytes());

    HnswOffheapAllocator allocator2 = HnswOffheapAllocator.getInstance();
    assertEquals("Must return same singleton instance", allocator1, allocator2);

    HnswOffheapAllocator.resetInstance();
    Configuration conf = new Configuration();
    conf.setLong(QueryServices.HNSW_OFFHEAP_MAX_BYTES_ATTRIB, 500L * 1024 * 1024);
    HnswOffheapAllocator allocator3 = HnswOffheapAllocator.getInstance(conf);
    assertEquals(500L * 1024 * 1024, allocator3.getMaxBytes());
  }

  @Test
  public void testConcurrentAllocationsUnderBudgetPressure() throws Exception {
    long maxBudget = 2000L;
    try (HnswOffheapAllocator allocator = new HnswOffheapAllocator(maxBudget)) {
      int numThreads = 8;
      int allocationsPerThread = 25;
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);
      AtomicInteger evictionCounter = new AtomicInteger(0);
      List<Callable<Void>> tasks = new ArrayList<>();

      for (int t = 0; t < numThreads; t++) {
        final int threadId = t;
        tasks.add(() -> {
          for (int i = 0; i < allocationsPerThread; i++) {
            SegmentKey key =
              SegmentKey.of("TBL", Bytes.toBytes("reg-" + threadId), Bytes.toBytes("seg-" + i));
            allocator.allocate(key, 200, k -> evictionCounter.incrementAndGet());
            allocator.recordAccess(key);
          }
          return null;
        });
      }

      List<Future<Void>> futures = executor.invokeAll(tasks);
      for (Future<Void> future : futures) {
        future.get(15, TimeUnit.SECONDS);
      }
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

      assertTrue("Allocated bytes must not exceed max budget",
        allocator.getAllocatedBytes() <= maxBudget);
      assertTrue("Evictions must have occurred under budget pressure",
        allocator.getEvictionCount() > 0);
      assertEquals("Listener eviction count matches allocator eviction count",
        allocator.getEvictionCount(), evictionCounter.get());
    }
  }
}
