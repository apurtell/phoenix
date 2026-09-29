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
package org.apache.phoenix.hbase.index.vector;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator.SegmentKey;
import org.apache.phoenix.hbase.index.hnsw.PhoenixMobReaderSupplier;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Region-scoped HNSW state for vector indexes backed by JVector and HBase MOB storage.
 * <p>
 * {@code HnswIndexManager} maintains:
 * <ul>
 * <li>A {@link ByteBuffer} (typically off-heap direct memory) containing the current immutable
 * graph segment loaded from MOB storage.</li>
 * <li>A concurrent {@link GraphIndexBuilder} serving as the in-memory mutable buffer for
 * incremental updates.</li>
 * <li>A bidirectional mapping between JVector graph ordinals and base table primary keys.</li>
 * <li>A {@link PhoenixMobReaderSupplier} bound to the active segment buffer.</li>
 * </ul>
 * Lifecycle management is coordinated with
 * {@link org.apache.phoenix.hbase.index.IndexRegionObserver} during region open ({@link #open()})
 * and region close ({@link #close()}).
 */
public class HnswIndexManager extends VectorIndexManager {

  private static final Logger LOG = LoggerFactory.getLogger(HnswIndexManager.class);

  public static final int DEFAULT_M = 16;
  public static final int DEFAULT_EF_CONSTRUCTION = 100;
  public static final float DEFAULT_ALPHA = 1.2f;
  public static final float DEFAULT_NEIGHBOR_OVERFLOW = 1.4f;

  public static final byte[] DEFAULT_SEGMENT_FAMILY = QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
  public static final byte[] DEFAULT_SEGMENT_QUALIFIER = Bytes.toBytes("_G");

  private final String tableName;
  private final byte[] regionName;
  private final Configuration conf;
  private final int dimension;
  private final VectorSimilarityFunction similarityFunction;
  private final int m;
  private final int efConstruction;
  private final float alpha;
  private final float neighborOverflow;
  private final boolean refineFinalGraph;
  private final int flushThreshold;
  private final long flushIntervalMs;
  private final byte[] segmentFamily;
  private final byte[] segmentQualifier;

  private final RegionCoprocessorEnvironment env;
  private final PTable table;

  // Active immutable segment state
  private volatile ByteBuffer segmentBuffer;
  private volatile PhoenixMobReaderSupplier readerSupplier;
  private volatile OnDiskGraphIndex onDiskGraphIndex;
  private volatile HnswOffheapAllocator allocator;
  private volatile byte[] activeSegmentRowKey;
  private volatile boolean evicted = false;
  private volatile MobSegmentLoader segmentLoader;

  // Mutable buffer state
  private final ConcurrentRandomAccessVectorValues mutableVectors;
  private volatile GraphIndexBuilder mutableBuilder;

  // Bidirectional ordinal <-> primary key mapping
  private final ConcurrentMap<Integer, byte[]> ordinalToKey = new ConcurrentHashMap<>();
  private final ConcurrentMap<ImmutableBytesPtr, Integer> keyToOrdinal = new ConcurrentHashMap<>();
  private final AtomicInteger nextOrdinal = new AtomicInteger(0);

  private volatile boolean closed = false;
  private volatile boolean initialized = false;

  /**
   * Constructs an {@link HnswIndexManager} from a coprocessor environment and index {@link PTable}.
   */
  public HnswIndexManager(RegionCoprocessorEnvironment env, PTable table) {
    this.env = env;
    this.table = table;
    this.conf = env != null ? env.getConfiguration() : new Configuration();
    this.regionName =
      (env != null && env.getRegion() != null && env.getRegion().getRegionInfo() != null)
        ? env.getRegion().getRegionInfo().getEncodedNameAsBytes()
        : new byte[0];
    this.tableName = table != null
      ? table.getName().getString()
      : ((env != null && env.getRegion() != null && env.getRegion().getTableDescriptor() != null)
        ? env.getRegion().getTableDescriptor().getTableName().getNameAsString()
        : "UNKNOWN");

    if (table != null && table.getDefaultFamilyName() != null) {
      this.segmentFamily = table.getDefaultFamilyName().getBytes();
    } else {
      this.segmentFamily = DEFAULT_SEGMENT_FAMILY;
    }
    this.segmentQualifier = DEFAULT_SEGMENT_QUALIFIER;

    PTable.VectorIndex vi = table != null ? table.getVectorIndex() : null;
    this.dimension = (vi != null && vi.getDimension() != null) ? vi.getDimension() : 128;
    this.similarityFunction = resolveSimilarityFunction(vi != null ? vi.getDistanceMetric() : null);
    this.m = (vi != null && vi.getHnswM() != null) ? vi.getHnswM() : DEFAULT_M;
    this.efConstruction = (vi != null && vi.getHnswEfConstruction() != null)
      ? vi.getHnswEfConstruction()
      : DEFAULT_EF_CONSTRUCTION;
    this.alpha =
      (vi != null && vi.getHnswAlpha() != null) ? vi.getHnswAlpha().floatValue() : DEFAULT_ALPHA;
    this.neighborOverflow = DEFAULT_NEIGHBOR_OVERFLOW;
    this.refineFinalGraph = true;

    this.flushThreshold = conf.getInt(QueryServices.HNSW_FLUSH_THRESHOLD_ATTRIB,
      QueryServicesOptions.DEFAULT_HNSW_FLUSH_THRESHOLD);
    this.flushIntervalMs = conf.getLong(QueryServices.HNSW_FLUSH_INTERVAL_MS_ATTRIB,
      QueryServicesOptions.DEFAULT_HNSW_FLUSH_INTERVAL_MS);

    this.mutableVectors = new ConcurrentRandomAccessVectorValues(dimension);
    // mutableBuilder and allocator are deferred to open() to avoid side effects
  }

  public HnswIndexManager(String tableName, byte[] regionName, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha) {
    this(tableName, regionName, conf, dimension, similarityFunction, m, efConstruction, alpha,
      DEFAULT_SEGMENT_FAMILY, DEFAULT_SEGMENT_QUALIFIER);
  }

  public HnswIndexManager(String tableName, byte[] regionName, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha,
    byte[] segmentFamily, byte[] segmentQualifier) {
    this.env = null;
    this.table = null;
    this.tableName = Preconditions.checkNotNull(tableName, "tableName cannot be null");
    this.regionName =
      regionName != null ? Arrays.copyOf(regionName, regionName.length) : new byte[0];
    this.conf = conf != null ? conf : new Configuration();
    this.dimension = dimension;
    this.similarityFunction =
      similarityFunction != null ? similarityFunction : VectorSimilarityFunction.COSINE;
    this.m = m > 0 ? m : DEFAULT_M;
    this.efConstruction = efConstruction > 0 ? efConstruction : DEFAULT_EF_CONSTRUCTION;
    this.alpha = alpha > 0.0f ? alpha : DEFAULT_ALPHA;
    this.neighborOverflow = DEFAULT_NEIGHBOR_OVERFLOW;
    this.refineFinalGraph = true;
    this.segmentFamily = segmentFamily != null
      ? Arrays.copyOf(segmentFamily, segmentFamily.length)
      : DEFAULT_SEGMENT_FAMILY;
    this.segmentQualifier = segmentQualifier != null
      ? Arrays.copyOf(segmentQualifier, segmentQualifier.length)
      : DEFAULT_SEGMENT_QUALIFIER;

    this.flushThreshold = this.conf.getInt(QueryServices.HNSW_FLUSH_THRESHOLD_ATTRIB,
      QueryServicesOptions.DEFAULT_HNSW_FLUSH_THRESHOLD);
    this.flushIntervalMs = this.conf.getLong(QueryServices.HNSW_FLUSH_INTERVAL_MS_ATTRIB,
      QueryServicesOptions.DEFAULT_HNSW_FLUSH_INTERVAL_MS);

    this.mutableVectors = new ConcurrentRandomAccessVectorValues(dimension);
    // mutableBuilder and allocator are deferred to open() to avoid side effects
  }

  public HnswIndexManager(String tableName, byte[] regionName, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha,
    byte[] segmentFamily, byte[] segmentQualifier, HnswOffheapAllocator allocator) {
    this(tableName, regionName, conf, dimension, similarityFunction, m, efConstruction, alpha,
      segmentFamily, segmentQualifier);
    this.allocator = allocator != null ? allocator : HnswOffheapAllocator.getInstance(this.conf);
  }

  private GraphIndexBuilder createGraphIndexBuilder() {
    return new GraphIndexBuilder(mutableVectors, similarityFunction, m, efConstruction, alpha,
      neighborOverflow, refineFinalGraph);
  }

  @Override
  public VectorIndexType getType() {
    return VectorIndexType.HNSW;
  }

  /**
   * Initializes the manager for the region: loads the immutable segment (if available) and runs the
   * catch-up recovery scan.
   */
  @Override
  public synchronized void open() throws IOException {
    checkNotClosed();
    if (initialized) {
      return;
    }
    LOG.info("Opening HnswIndexManager for region {} of table {}", Bytes.toStringBinary(regionName),
      tableName);
    // Deferred from constructor to avoid side effects
    if (this.mutableBuilder == null) {
      this.mutableBuilder = createGraphIndexBuilder();
    }
    if (this.allocator == null) {
      this.allocator = HnswOffheapAllocator.getInstance(this.conf);
    }
    runCatchUpRecovery();
    this.initialized = true;
  }

  /** Crash recovery catch-up scan to replay mutations committed after segment construction. */
  protected void runCatchUpRecovery() throws IOException {
    if (env != null && env.getRegion() != null) {
      Region region = env.getRegion();
      LOG.debug("Running catch-up recovery scan for region {}",
        region.getRegionInfo().getEncodedName());
      // Placeholder hook for Phase 5.5 bounded replay via Scan.setTimeRange()
    }
  }

  /** Functional interface for reloading an evicted segment on demand from MOB storage. */
  @FunctionalInterface
  public interface MobSegmentLoader {
    ByteBuffer load() throws IOException;
  }

  /**
   * Loads an immutable graph segment from an off-heap or direct buffer into memory. Creates a new
   * {@link PhoenixMobReaderSupplier} and loads the {@link OnDiskGraphIndex}.
   */
  public synchronized void loadSegment(ByteBuffer directBuffer) throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(directBuffer, "directBuffer cannot be null");
    if (activeSegmentRowKey == null) {
      this.activeSegmentRowKey = Bytes.toBytes("seg-default");
    }
    if (allocator != null) {
      allocator.register(getSegmentKey(), directBuffer, evictedKey -> {
        try {
          evictSegment();
        } catch (IOException e) {
          LOG.warn("Error evicting segment {}", evictedKey, e);
        }
      });
    }
    loadSegmentInternal(directBuffer);
  }

  /**
   * Loads an immutable graph segment from an off-heap or direct buffer into memory with an explicit
   * segment row key and an optional {@link MobSegmentLoader} for on-demand re-materialization.
   */
  public synchronized void loadSegment(byte[] segmentRowKey, ByteBuffer directBuffer,
    MobSegmentLoader loader) throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(segmentRowKey, "segmentRowKey cannot be null");
    Preconditions.checkNotNull(directBuffer, "directBuffer cannot be null");
    if (
      activeSegmentRowKey != null && !Bytes.equals(activeSegmentRowKey, segmentRowKey)
        && allocator != null
    ) {
      allocator.release(getSegmentKey());
    }
    this.activeSegmentRowKey = Arrays.copyOf(segmentRowKey, segmentRowKey.length);
    this.segmentLoader = loader;
    if (allocator != null) {
      allocator.register(getSegmentKey(), directBuffer, evictedKey -> {
        try {
          evictSegment();
        } catch (IOException e) {
          LOG.warn("Error evicting segment {}", evictedKey, e);
        }
      });
    }
    loadSegmentInternal(directBuffer);
  }

  public synchronized void loadSegment(byte[] segmentRowKey, ByteBuffer directBuffer)
    throws IOException {
    loadSegment(segmentRowKey, directBuffer, null);
  }

  private void loadSegmentInternal(ByteBuffer directBuffer) throws IOException {
    closeActiveSegment();
    this.segmentBuffer = directBuffer;
    this.readerSupplier = new PhoenixMobReaderSupplier(directBuffer);
    this.onDiskGraphIndex = OnDiskGraphIndex.load(readerSupplier);
    this.evicted = false;
    LOG.info("Loaded immutable HNSW graph segment (size={} nodes) for region {}",
      onDiskGraphIndex.size(), Bytes.toStringBinary(regionName));
  }

  /**
   * Evicts the active immutable segment from off-heap memory, releasing its buffer and closing
   * reader resources. Retains segment metadata so that subsequent query access can re-materialize
   * the segment on demand from MOB storage.
   */
  public synchronized void evictSegment() throws IOException {
    if (closed || evicted) {
      return;
    }
    LOG.info("Evicting HNSW segment {} for table {} region {}",
      activeSegmentRowKey != null ? Bytes.toStringBinary(activeSegmentRowKey) : "none", tableName,
      Bytes.toStringBinary(regionName));
    this.evicted = true;
    closeActiveSegment();
  }

  /**
   * Re-materializes an evicted segment on demand from MOB storage if it is currently evicted.
   */
  public synchronized void ensureSegmentLoaded() throws IOException {
    checkNotClosed();
    if (segmentBuffer != null && onDiskGraphIndex != null && !evicted) {
      if (allocator != null && activeSegmentRowKey != null) {
        allocator.recordAccess(getSegmentKey());
      }
      return;
    }
    if (evicted && segmentLoader != null) {
      LOG.info("Re-materializing evicted HNSW segment {} from MOB storage for table {} region {}",
        activeSegmentRowKey != null ? Bytes.toStringBinary(activeSegmentRowKey) : "none", tableName,
        Bytes.toStringBinary(regionName));
      ByteBuffer reloadedBuffer = segmentLoader.load();
      if (reloadedBuffer == null) {
        throw new IOException("Failed to re-materialize segment from MOB storage for rowKey: "
          + Bytes.toStringBinary(activeSegmentRowKey));
      }
      loadSegmentInternal(reloadedBuffer);
      this.evicted = false;
      if (allocator != null && activeSegmentRowKey != null) {
        allocator.recordAccess(getSegmentKey());
      }
    }
  }

  private void closeActiveSegment() throws IOException {
    if (onDiskGraphIndex != null) {
      try {
        onDiskGraphIndex.close();
      } finally {
        onDiskGraphIndex = null;
      }
    }
    if (readerSupplier != null) {
      try {
        readerSupplier.close();
      } finally {
        readerSupplier = null;
      }
    }
    this.segmentBuffer = null;
  }

  // -------------------------------------------------------------------------------------
  // Read and Write Pathways for Transparent MOB Segment Retrieval (Step 3.4)
  // -------------------------------------------------------------------------------------

  /**
   * Returns the column family used for segment cell storage.
   */
  public byte[] getSegmentFamily() {
    return Arrays.copyOf(segmentFamily, segmentFamily.length);
  }

  /**
   * Returns the column qualifier used for segment cell storage.
   */
  public byte[] getSegmentQualifier() {
    return Arrays.copyOf(segmentQualifier, segmentQualifier.length);
  }

  /**
   * Creates a standard HBase {@link Put} mutation for storing a serialized graph segment using the
   * manager's default segment family and qualifier.
   */
  public Put createSegmentPut(byte[] segmentRowKey, byte[] segmentPayload) {
    return createSegmentPut(segmentRowKey, this.segmentFamily, this.segmentQualifier,
      segmentPayload);
  }

  /**
   * Creates a standard HBase {@link Put} mutation for storing a serialized graph segment.
   */
  public static Put createSegmentPut(byte[] segmentRowKey, byte[] family, byte[] qualifier,
    byte[] segmentPayload) {
    Preconditions.checkNotNull(segmentRowKey, "segmentRowKey cannot be null");
    Preconditions.checkNotNull(family, "family cannot be null");
    Preconditions.checkNotNull(qualifier, "qualifier cannot be null");
    Preconditions.checkNotNull(segmentPayload, "segmentPayload cannot be null");

    Put put = new Put(segmentRowKey);
    put.addColumn(family, qualifier, segmentPayload);
    return put;
  }

  /**
   * Creates a standard HBase {@link Put} mutation for storing a serialized graph segment with an
   * explicit timestamp.
   */
  public static Put createSegmentPut(byte[] segmentRowKey, byte[] family, byte[] qualifier,
    long timestamp, byte[] segmentPayload) {
    Preconditions.checkNotNull(segmentRowKey, "segmentRowKey cannot be null");
    Preconditions.checkNotNull(family, "family cannot be null");
    Preconditions.checkNotNull(qualifier, "qualifier cannot be null");
    Preconditions.checkNotNull(segmentPayload, "segmentPayload cannot be null");

    Put put = new Put(segmentRowKey, timestamp);
    put.addColumn(family, qualifier, timestamp, segmentPayload);
    return put;
  }

  /**
   * Creates a standard HBase {@link Put} mutation for storing a serialized graph segment from a
   * {@link ByteBuffer}.
   */
  public static Put createSegmentPut(byte[] segmentRowKey, byte[] family, byte[] qualifier,
    ByteBuffer buffer) {
    Preconditions.checkNotNull(buffer, "buffer cannot be null");
    byte[] bytes;
    if (
      buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.position() == 0
        && buffer.limit() == buffer.capacity()
    ) {
      bytes = buffer.array();
    } else {
      ByteBuffer dup = buffer.duplicate();
      bytes = new byte[dup.remaining()];
      dup.get(bytes);
    }
    return createSegmentPut(segmentRowKey, family, qualifier, bytes);
  }

  /**
   * Writes a serialized graph segment to an HBase {@link Table} via standard {@link Put}. When
   * written to a MOB-enabled family, HBase routes the payload to MOB storage.
   */
  public void writeSegment(Table table, byte[] segmentRowKey, byte[] segmentPayload)
    throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(table, "table cannot be null");
    Put put = createSegmentPut(segmentRowKey, segmentPayload);
    table.put(put);
  }

  /**
   * Writes a serialized graph segment to an HBase {@link Table} via standard {@link Put}.
   */
  public void writeSegment(Table table, byte[] segmentRowKey, ByteBuffer segmentPayload)
    throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(table, "table cannot be null");
    Put put =
      createSegmentPut(segmentRowKey, this.segmentFamily, this.segmentQualifier, segmentPayload);
    table.put(put);
  }

  /**
   * Writes a serialized graph segment to an HBase {@link Region} via standard {@link Put}.
   */
  public void writeSegment(Region region, byte[] segmentRowKey, byte[] segmentPayload)
    throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(region, "region cannot be null");
    Put put = createSegmentPut(segmentRowKey, segmentPayload);
    region.put(put);
  }

  /**
   * Writes a serialized graph segment to an HBase {@link Region} via standard {@link Put}.
   */
  public void writeSegment(Region region, byte[] segmentRowKey, ByteBuffer segmentPayload)
    throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(region, "region cannot be null");
    Put put =
      createSegmentPut(segmentRowKey, this.segmentFamily, this.segmentQualifier, segmentPayload);
    region.put(put);
  }

  /**
   * Writes a serialized graph segment using the coprocessor environment's region via standard
   * {@link Put}.
   */
  public void writeSegment(byte[] segmentRowKey, byte[] segmentPayload) throws IOException {
    checkNotClosed();
    if (env == null || env.getRegion() == null) {
      throw new IllegalStateException("Coprocessor Region is not available in HnswIndexManager");
    }
    writeSegment(env.getRegion(), segmentRowKey, segmentPayload);
  }

  /**
   * Writes a serialized graph segment using the coprocessor environment's region via standard
   * {@link Put}.
   */
  public void writeSegment(byte[] segmentRowKey, ByteBuffer segmentPayload) throws IOException {
    checkNotClosed();
    if (env == null || env.getRegion() == null) {
      throw new IllegalStateException("Coprocessor Region is not available in HnswIndexManager");
    }
    writeSegment(env.getRegion(), segmentRowKey, segmentPayload);
  }

  /**
   * Creates a standard HBase {@link Get} operation for retrieving a graph segment using the
   * manager's default family and qualifier.
   */
  public Get createSegmentGet(byte[] segmentRowKey) {
    return createSegmentGet(segmentRowKey, this.segmentFamily, this.segmentQualifier);
  }

  /**
   * Creates a standard HBase {@link Get} operation for retrieving a graph segment with specified
   * family and qualifier.
   */
  public static Get createSegmentGet(byte[] segmentRowKey, byte[] family, byte[] qualifier) {
    Preconditions.checkNotNull(segmentRowKey, "segmentRowKey cannot be null");
    Preconditions.checkNotNull(family, "family cannot be null");
    Preconditions.checkNotNull(qualifier, "qualifier cannot be null");

    Get get = new Get(segmentRowKey);
    get.addColumn(family, qualifier);
    return get;
  }

  /**
   * Copies the payload of an HBase {@link Cell} into an off-heap direct {@link ByteBuffer}.
   * Standard HBase Get and Scan operations on MOB-enabled column families transparently resolve the
   * MOB cell, returning the complete binary payload in the Cell value array.
   * @param cell the cell containing the serialized graph segment payload
   * @return a direct {@link ByteBuffer} positioned at 0 and limited to the payload size, or null if
   *         cell is null
   */
  public static ByteBuffer copyCellToDirectByteBuffer(Cell cell) {
    if (cell == null) {
      return null;
    }
    int length = cell.getValueLength();
    ByteBuffer directBuffer = ByteBuffer.allocateDirect(length);
    directBuffer.put(cell.getValueArray(), cell.getValueOffset(), length);
    directBuffer.flip();
    return directBuffer;
  }

  /**
   * Copies a byte array payload into an off-heap direct {@link ByteBuffer}.
   * @param payload the serialized graph segment payload
   * @return a direct {@link ByteBuffer} positioned at 0 and limited to payload size, or null if
   *         payload is null
   */
  public static ByteBuffer copyPayloadToDirectByteBuffer(byte[] payload) {
    if (payload == null) {
      return null;
    }
    ByteBuffer directBuffer = ByteBuffer.allocateDirect(payload.length);
    directBuffer.put(payload);
    directBuffer.flip();
    return directBuffer;
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Table} via standard {@link Get}, copying
   * the payload into an off-heap direct {@link ByteBuffer}. HBase transparently resolves MOB cells
   * during this retrieval.
   * @param table         the HBase table
   * @param segmentRowKey the row key of the segment
   * @return direct {@link ByteBuffer} containing the complete segment payload, or null if not found
   */
  public ByteBuffer readSegmentPayload(Table table, byte[] segmentRowKey) throws IOException {
    return readSegmentPayload(table, segmentRowKey, this.segmentFamily, this.segmentQualifier);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Table} via standard {@link Get} with
   * custom family and qualifier, copying the payload into an off-heap direct {@link ByteBuffer}.
   */
  public ByteBuffer readSegmentPayload(Table table, byte[] segmentRowKey, byte[] family,
    byte[] qualifier) throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(table, "table cannot be null");
    Get get = createSegmentGet(segmentRowKey, family, qualifier);
    Result result = table.get(get);
    if (result == null || result.isEmpty()) {
      return null;
    }
    Cell cell = result.getColumnLatestCell(family, qualifier);
    if (cell == null) {
      return null;
    }
    if (allocator != null) {
      SegmentKey key = SegmentKey.of(tableName, regionName, segmentRowKey);
      return allocator.allocate(key, cell, evictedKey -> {
        try {
          evictSegment();
        } catch (IOException e) {
          LOG.warn("Error evicting segment {}", evictedKey, e);
        }
      });
    }
    return copyCellToDirectByteBuffer(cell);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Region} via standard {@link Get}, copying
   * the payload into an off-heap direct {@link ByteBuffer}. HBase transparently resolves MOB cells
   * during this retrieval.
   * @param region        the region
   * @param segmentRowKey the row key of the segment
   * @return direct {@link ByteBuffer} containing the complete segment payload, or null if not found
   */
  public ByteBuffer readSegmentPayload(Region region, byte[] segmentRowKey) throws IOException {
    return readSegmentPayload(region, segmentRowKey, this.segmentFamily, this.segmentQualifier);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Region} via standard {@link Get} with
   * custom family and qualifier, copying the payload into an off-heap direct {@link ByteBuffer}.
   */
  public ByteBuffer readSegmentPayload(Region region, byte[] segmentRowKey, byte[] family,
    byte[] qualifier) throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(region, "region cannot be null");
    Get get = createSegmentGet(segmentRowKey, family, qualifier);
    Result result = region.get(get);
    if (result == null || result.isEmpty()) {
      return null;
    }
    Cell cell = result.getColumnLatestCell(family, qualifier);
    if (cell == null) {
      return null;
    }
    if (allocator != null) {
      SegmentKey key = SegmentKey.of(tableName, regionName, segmentRowKey);
      return allocator.allocate(key, cell, evictedKey -> {
        try {
          evictSegment();
        } catch (IOException e) {
          LOG.warn("Error evicting segment {}", evictedKey, e);
        }
      });
    }
    return copyCellToDirectByteBuffer(cell);
  }

  /**
   * Reads a serialized graph segment from the coprocessor environment's region via standard
   * {@link Get}.
   */
  public ByteBuffer readSegmentPayload(byte[] segmentRowKey) throws IOException {
    checkNotClosed();
    if (env == null || env.getRegion() == null) {
      throw new IllegalStateException("Coprocessor Region is not available in HnswIndexManager");
    }
    return readSegmentPayload(env.getRegion(), segmentRowKey);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Table} via standard {@link Get}, copies
   * the resolved MOB payload into an off-heap direct {@link ByteBuffer}, and loads it as the active
   * immutable segment.
   */
  public synchronized void loadSegment(Table table, byte[] segmentRowKey) throws IOException {
    loadSegment(table, segmentRowKey, this.segmentFamily, this.segmentQualifier);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Table} via standard {@link Get} with
   * custom family and qualifier, and loads it as the active immutable segment.
   */
  public synchronized void loadSegment(Table table, byte[] segmentRowKey, byte[] family,
    byte[] qualifier) throws IOException {
    checkNotClosed();
    if (
      activeSegmentRowKey != null && !Bytes.equals(activeSegmentRowKey, segmentRowKey)
        && allocator != null
    ) {
      allocator.release(getSegmentKey());
    }
    this.activeSegmentRowKey = Arrays.copyOf(segmentRowKey, segmentRowKey.length);
    this.segmentLoader = () -> readSegmentPayload(table, activeSegmentRowKey, family, qualifier);
    ByteBuffer directBuffer = segmentLoader.load();
    if (directBuffer == null) {
      throw new IOException(
        "Segment cell not found for rowKey: " + Bytes.toStringBinary(segmentRowKey));
    }
    loadSegmentInternal(directBuffer);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Region} via standard {@link Get}, copies
   * the resolved MOB payload into an off-heap direct {@link ByteBuffer}, and loads it as the active
   * immutable segment.
   */
  public synchronized void loadSegment(Region region, byte[] segmentRowKey) throws IOException {
    loadSegment(region, segmentRowKey, this.segmentFamily, this.segmentQualifier);
  }

  /**
   * Reads a serialized graph segment from an HBase {@link Region} via standard {@link Get} with
   * custom family and qualifier, and loads it as the active immutable segment.
   */
  public synchronized void loadSegment(Region region, byte[] segmentRowKey, byte[] family,
    byte[] qualifier) throws IOException {
    checkNotClosed();
    if (
      activeSegmentRowKey != null && !Bytes.equals(activeSegmentRowKey, segmentRowKey)
        && allocator != null
    ) {
      allocator.release(getSegmentKey());
    }
    this.activeSegmentRowKey = Arrays.copyOf(segmentRowKey, segmentRowKey.length);
    this.segmentLoader = () -> readSegmentPayload(region, activeSegmentRowKey, family, qualifier);
    ByteBuffer directBuffer = segmentLoader.load();
    if (directBuffer == null) {
      throw new IOException(
        "Segment cell not found for rowKey: " + Bytes.toStringBinary(segmentRowKey));
    }
    loadSegmentInternal(directBuffer);
  }

  /**
   * Reads a serialized graph segment from the coprocessor environment's region via standard
   * {@link Get} and loads it as the active immutable segment.
   */
  public synchronized void loadSegment(byte[] segmentRowKey) throws IOException {
    checkNotClosed();
    if (env == null || env.getRegion() == null) {
      throw new IllegalStateException("Coprocessor Region is not available in HnswIndexManager");
    }
    loadSegment(env.getRegion(), segmentRowKey);
  }

  /**
   * Incremental upsert of a vector into the mutable buffer. If a vector already exists for the
   * given {@code rowKey}, the prior ordinal is marked deleted.
   */
  public synchronized void upsert(byte[] rowKey, VectorFloat<?> vector) {
    checkNotClosed();
    Preconditions.checkNotNull(rowKey, "rowKey cannot be null");
    Preconditions.checkNotNull(vector, "vector cannot be null");

    ImmutableBytesPtr keyPtr = new ImmutableBytesPtr(rowKey);
    Integer existingOrdinal = keyToOrdinal.get(keyPtr);
    if (existingOrdinal != null) {
      mutableBuilder.markNodeDeleted(existingOrdinal);
      ordinalToKey.remove(existingOrdinal);
      // Clean up stale vector to prevent memory leak from accumulated deleted vectors
      mutableVectors.removeVector(existingOrdinal);
    }

    int newOrdinal = nextOrdinal.getAndIncrement();
    mutableVectors.putVector(newOrdinal, vector);
    byte[] keyCopy = Arrays.copyOf(rowKey, rowKey.length);
    ordinalToKey.put(newOrdinal, keyCopy);
    keyToOrdinal.put(new ImmutableBytesPtr(keyCopy), newOrdinal);

    mutableBuilder.addGraphNode(newOrdinal, vector);
  }

  /** Convenience overload for float arrays. */
  public void upsert(byte[] rowKey, float[] vector) {
    Preconditions.checkNotNull(vector, "vector cannot be null");
    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    upsert(rowKey, vts.createFloatVector(vector));
  }

  /** Convenience overload for serialized Phoenix vector column bytes. */
  public void upsert(byte[] rowKey, byte[] vectorBytes) {
    Preconditions.checkNotNull(vectorBytes, "vectorBytes cannot be null");
    float[] floats = (float[]) PVectorFloat.INSTANCE.toObject(vectorBytes);
    upsert(rowKey, floats);
  }

  /** Incremental deletion of a primary key from the graph. */
  public synchronized void delete(byte[] rowKey) {
    checkNotClosed();
    Preconditions.checkNotNull(rowKey, "rowKey cannot be null");

    ImmutableBytesPtr keyPtr = new ImmutableBytesPtr(rowKey);
    Integer ordinal = keyToOrdinal.remove(keyPtr);
    if (ordinal != null) {
      ordinalToKey.remove(ordinal);
      mutableBuilder.markNodeDeleted(ordinal);
      // Clean up stale vector to prevent memory leak from accumulated deleted vectors
      mutableVectors.removeVector(ordinal);
    }
  }

  /** Returns the base table primary key corresponding to a JVector graph ordinal. */
  public byte[] getRowKeyForOrdinal(int ordinal) {
    return ordinalToKey.get(ordinal);
  }

  /** Returns the JVector graph ordinal corresponding to a base table primary key. */
  public Integer getOrdinalForRowKey(byte[] rowKey) {
    if (rowKey == null) {
      return null;
    }
    return keyToOrdinal.get(new ImmutableBytesPtr(rowKey));
  }

  /**
   * Searches the immutable on-disk graph segment for approximate nearest neighbors. If the
   * immutable segment was previously evicted by {@link HnswOffheapAllocator}, it is re-materialized
   * on demand from MOB storage before executing the search.
   */
  public SearchResult search(VectorFloat<?> queryVector, int topK) throws IOException {
    return search(queryVector, topK, 0);
  }

  /**
   * Searches the immutable on-disk graph segment for approximate nearest neighbors with specified
   * efSearch parameter.
   */
  public SearchResult search(VectorFloat<?> queryVector, int topK, int efSearch)
    throws IOException {
    checkNotClosed();
    ensureSegmentLoaded();
    if (onDiskGraphIndex == null) {
      return null;
    }
    if (allocator != null && activeSegmentRowKey != null) {
      allocator.recordAccess(getSegmentKey());
    }
    try (OnDiskGraphIndex.View view = onDiskGraphIndex.getView()) {
      if (efSearch > 0) {
        return GraphSearcher.search(queryVector, topK, efSearch, view, similarityFunction,
          onDiskGraphIndex, Bits.ALL);
      } else {
        return GraphSearcher.search(queryVector, topK, view, similarityFunction, onDiskGraphIndex,
          Bits.ALL);
      }
    }
  }

  /** Searches the in-memory mutable graph index for approximate nearest neighbors. */
  public SearchResult searchMutable(VectorFloat<?> queryVector, int topK) {
    checkNotClosed();
    return GraphSearcher.search(queryVector, topK, mutableVectors, similarityFunction,
      mutableBuilder.getGraph(), Bits.ALL);
  }

  @Override
  public void onMutation(IndexMaintainer indexMaintainer, Put currentDataRowState,
    Put nextDataRowState, ValueGetter nextDataRowVG, Put indexPut,
    byte[] indexRowKeyForCurrentDataRow, boolean isVectorUnchanged, long ts) {
    if (closed || !initialized) {
      return;
    }
    if (nextDataRowState != null) {
      if (isVectorUnchanged) {
        return;
      }
      // Incremental upsert hook (full column extraction wired in Phase 5)
    } else if (currentDataRowState != null) {
      delete(currentDataRowState.getRow());
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    this.closed = true;
    LOG.info("Closing HnswIndexManager for region {} of table {}", Bytes.toStringBinary(regionName),
      tableName);

    try {
      if (allocator != null && activeSegmentRowKey != null) {
        allocator.release(getSegmentKey());
      }
    } catch (Exception e) {
      LOG.warn("Error releasing segment from HnswOffheapAllocator", e);
    }

    try {
      closeActiveSegment();
    } catch (Exception e) {
      LOG.warn("Error closing active segment during HnswIndexManager shutdown", e);
    }

    if (mutableBuilder != null) {
      try {
        mutableBuilder.close();
      } catch (Exception e) {
        LOG.warn("Error closing mutableBuilder during HnswIndexManager shutdown", e);
      }
    }

    ordinalToKey.clear();
    keyToOrdinal.clear();
    mutableVectors.clear();
  }

  private void checkNotClosed() {
    if (closed) {
      throw new IllegalStateException("HnswIndexManager is closed");
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  public boolean isEvicted() {
    return evicted;
  }

  public ByteBuffer getRawSegmentBuffer() {
    return segmentBuffer;
  }

  public byte[] getActiveSegmentRowKey() {
    return activeSegmentRowKey != null
      ? Arrays.copyOf(activeSegmentRowKey, activeSegmentRowKey.length)
      : null;
  }

  public SegmentKey getSegmentKey() {
    return SegmentKey.of(tableName, regionName,
      activeSegmentRowKey != null ? activeSegmentRowKey : new byte[0]);
  }

  public HnswOffheapAllocator getAllocator() {
    return allocator;
  }

  public void setAllocator(HnswOffheapAllocator allocator) {
    this.allocator = allocator;
  }

  public ByteBuffer getSegmentBuffer() {
    if ((segmentBuffer == null || evicted) && segmentLoader != null && !closed) {
      try {
        ensureSegmentLoaded();
      } catch (IOException e) {
        LOG.error("Failed to re-materialize segment buffer from MOB storage", e);
        throw new RuntimeException(e);
      }
    }
    if (allocator != null && activeSegmentRowKey != null) {
      allocator.recordAccess(getSegmentKey());
    }
    return segmentBuffer;
  }

  public PhoenixMobReaderSupplier getReaderSupplier() {
    if ((readerSupplier == null || evicted) && segmentLoader != null && !closed) {
      try {
        ensureSegmentLoaded();
      } catch (IOException e) {
        LOG.error("Failed to re-materialize reader supplier from MOB storage", e);
        throw new RuntimeException(e);
      }
    }
    if (allocator != null && activeSegmentRowKey != null) {
      allocator.recordAccess(getSegmentKey());
    }
    return readerSupplier;
  }

  public OnDiskGraphIndex getOnDiskGraphIndex() {
    if ((onDiskGraphIndex == null || evicted) && segmentLoader != null && !closed) {
      try {
        ensureSegmentLoaded();
      } catch (IOException e) {
        LOG.error("Failed to re-materialize segment from MOB storage", e);
        throw new RuntimeException(e);
      }
    }
    if (allocator != null && activeSegmentRowKey != null) {
      allocator.recordAccess(getSegmentKey());
    }
    return onDiskGraphIndex;
  }

  public GraphIndexBuilder getMutableBuilder() {
    return mutableBuilder;
  }

  public int getMutableNodeCount() {
    return mutableVectors.size();
  }

  public int getDimension() {
    return dimension;
  }

  public VectorSimilarityFunction getSimilarityFunction() {
    return similarityFunction;
  }

  public int getM() {
    return m;
  }

  public int getEfConstruction() {
    return efConstruction;
  }

  public float getAlpha() {
    return alpha;
  }

  public Map<Integer, byte[]> getOrdinalToKeyMap() {
    return Collections.unmodifiableMap(ordinalToKey);
  }

  public Map<ImmutableBytesPtr, Integer> getKeyToOrdinalMap() {
    return Collections.unmodifiableMap(keyToOrdinal);
  }

  public String getTableName() {
    return tableName;
  }

  public byte[] getRegionName() {
    return Arrays.copyOf(regionName, regionName.length);
  }

  /** Resolves a distance metric string to a JVector {@link VectorSimilarityFunction}. */
  public static VectorSimilarityFunction resolveSimilarityFunction(String metric) {
    if (metric == null) {
      return VectorSimilarityFunction.COSINE;
    }
    String upper = metric.trim().toUpperCase();
    switch (upper) {
      case "EUCLIDEAN":
      case "L2":
      case "L2_SQUARED":
        return VectorSimilarityFunction.EUCLIDEAN;
      case "DOT_PRODUCT":
      case "INNER_PRODUCT":
      case "DOT":
        return VectorSimilarityFunction.DOT_PRODUCT;
      case "COSINE":
      default:
        return VectorSimilarityFunction.COSINE;
    }
  }

  /** Thread-safe in-memory {@link RandomAccessVectorValues} backing the mutable buffer. */
  public static class ConcurrentRandomAccessVectorValues implements RandomAccessVectorValues {

    private final ConcurrentMap<Integer, VectorFloat<?>> vectorMap;
    private final int dimension;
    /**
     * Monotonically increasing ordinal bound. Tracks the highest ordinal ever assigned + 1. Used by
     * {@link #size()} to return a correct upper bound over the ordinal space, even when the vector
     * map has gaps from deleted nodes. JVector's GraphIndexBuilder uses this bound during neighbor
     * traversal and search result sizing.
     */
    private final AtomicInteger ordinalBound = new AtomicInteger(0);

    public ConcurrentRandomAccessVectorValues(int dimension) {
      this(dimension, new ConcurrentHashMap<>());
    }

    public ConcurrentRandomAccessVectorValues(int dimension,
      ConcurrentMap<Integer, VectorFloat<?>> vectorMap) {
      this.dimension = dimension;
      this.vectorMap = vectorMap;
    }

    public void putVector(int ordinal, VectorFloat<?> vector) {
      vectorMap.put(ordinal, vector);
      // Atomically advance the ordinal bound if this ordinal exceeds the current bound
      ordinalBound.accumulateAndGet(ordinal + 1, Math::max);
    }

    public VectorFloat<?> removeVector(int ordinal) {
      return vectorMap.remove(ordinal);
    }

    public void clear() {
      vectorMap.clear();
      ordinalBound.set(0);
    }

    @Override
    public int size() {
      return ordinalBound.get();
    }

    @Override
    public int dimension() {
      return dimension;
    }

    @Override
    public VectorFloat<?> getVector(int ordinal) {
      return vectorMap.get(ordinal);
    }

    @Override
    public boolean isValueShared() {
      return false;
    }

    @Override
    public RandomAccessVectorValues copy() {
      return new ConcurrentRandomAccessVectorValues(dimension, vectorMap);
    }
  }
}
