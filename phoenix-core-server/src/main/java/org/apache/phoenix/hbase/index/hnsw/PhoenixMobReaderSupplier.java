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

import io.github.jbellis.jvector.disk.ByteBufferReader;
import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Adapter implementing JVector's {@link ReaderSupplier} over an off-heap (or direct)
 * {@link ByteBuffer} representing a serialized HNSW graph segment stored in HBase MOB.
 * <p>
 * For each concurrent query thread calling {@link #get()}, this supplier creates a
 * {@link ByteBufferReader} wrapping a duplicated buffer reference
 * ({@code segmentBuffer.duplicate()}). This provides each query thread with independent position
 * and limit state over the shared memory segment without copying underlying byte data.
 */
public class PhoenixMobReaderSupplier implements ReaderSupplier {

  private final ByteBuffer segmentBuffer;
  private volatile boolean closed = false;

  /** Constructs a supplier backed by the given segment buffer. */
  public PhoenixMobReaderSupplier(ByteBuffer segmentBuffer) {
    Preconditions.checkNotNull(segmentBuffer, "segmentBuffer cannot be null");
    // Maintain an independent buffer reference positioned at 0
    this.segmentBuffer = segmentBuffer.duplicate();
    this.segmentBuffer.position(0);
  }

  @Override
  public RandomAccessReader get() throws IOException {
    if (closed) {
      throw new IOException("PhoenixMobReaderSupplier has been closed");
    }
    return new ByteBufferReader(segmentBuffer.duplicate());
  }

  @Override
  public void prefetch(long offset, long length) {
    // In-memory / off-heap buffers are already resident in memory; prefetch is a no-op.
  }

  @Override
  public void close() {
    this.closed = true;
  }

  public boolean isClosed() {
    return closed;
  }

}
