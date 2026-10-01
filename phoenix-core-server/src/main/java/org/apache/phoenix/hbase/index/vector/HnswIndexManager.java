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

import io.github.jbellis.jvector.disk.ByteBufferReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.NodesIterator;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RemappedRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.AbstractGraphIndexWriter;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndexWriter;
import io.github.jbellis.jvector.graph.disk.feature.Feature;
import io.github.jbellis.jvector.graph.disk.feature.FeatureId;
import io.github.jbellis.jvector.graph.disk.feature.FusedPQ;
import io.github.jbellis.jvector.graph.disk.feature.InlineVectors;
import io.github.jbellis.jvector.graph.disk.feature.NVQ;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.quantization.NVQuantization;
import io.github.jbellis.jvector.quantization.PQVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.hbase.index.covered.update.ColumnReference;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator.SegmentKey;
import org.apache.phoenix.hbase.index.hnsw.PhoenixMobReaderSupplier;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.mapreduce.vector.HnswGraphBuildMapper;
import org.apache.phoenix.mapreduce.vector.HnswPqCodebookTrainer;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PVectorDouble;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.QueryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;
import org.apache.phoenix.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

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

  public static final int ORDINAL_MAPPING_MAGIC = 0x504B4D50;

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
  private final String quantizationType;
  private final RegionCoprocessorEnvironment env;
  private final PTable table;
  private volatile ByteBuffer segmentBuffer;
  private volatile PhoenixMobReaderSupplier readerSupplier;
  private volatile OnDiskGraphIndex onDiskGraphIndex;
  private volatile HnswOffheapAllocator allocator;
  private volatile byte[] activeSegmentRowKey;
  private volatile boolean evicted = false;
  private volatile MobSegmentLoader segmentLoader;
  private volatile SegmentMetadata activeSegmentMetadata;
  private volatile byte[] regionStartKey;
  private volatile byte[] regionEndKey;
  private volatile ConnectionSupplier connectionSupplier;
  private volatile TableSupplier tableSupplier;
  private volatile TableSupplier baseTableSupplier;
  private volatile Region region;
  private volatile int lastRecoveredRows = 0;
  private volatile int lastRecoveredUpserts = 0;
  private volatile int lastRecoveredDeletes = 0;
  private volatile ConcurrentRandomAccessVectorValues mutableVectors;
  private volatile GraphIndexBuilder mutableBuilder;
  private volatile GraphIndexBuilder flushingSnapshotBuilder;
  private volatile ConcurrentRandomAccessVectorValues flushingSnapshotVectors;
  private volatile Map<Integer, byte[]> flushingSnapshotOrdinalToKey;
  private final AtomicBoolean isFlushing = new AtomicBoolean(false);
  private final ReentrantLock flushLock = new ReentrantLock();
  private ScheduledExecutorService flushScheduler;
  private ScheduledFuture<?> scheduledFlushTask;
  private final ConcurrentMap<Integer, byte[]> ordinalToKey = new ConcurrentHashMap<>();
  private final ConcurrentMap<ImmutableBytesPtr, Integer> keyToOrdinal = new ConcurrentHashMap<>();
  private final AtomicInteger nextOrdinal = new AtomicInteger(0);
  /**
   * Ordinals held by {@link #mutableBuilder} that are not marked deleted. Membership is recorded on
   * insert rather than inferred from {@link #ordinalToKey}, whose ordinals also cover the immutable
   * on-disk segment, or from {@link #mutableVectors}, which can retain ordinals of a superseded
   * mutable graph that overlap the ordinal space of a newly loaded segment.
   */
  private final Set<Integer> liveMutableOrdinals = ConcurrentHashMap.newKeySet();
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
    this.quantizationType =
      (vi != null && vi.getQuantizationType() != null) ? vi.getQuantizationType() : "NONE";

    this.flushThreshold = conf.getInt(QueryServices.HNSW_FLUSH_THRESHOLD_ATTRIB,
      QueryServicesOptions.DEFAULT_HNSW_FLUSH_THRESHOLD);
    this.flushIntervalMs = conf.getLong(QueryServices.HNSW_FLUSH_INTERVAL_MS_ATTRIB,
      QueryServicesOptions.DEFAULT_HNSW_FLUSH_INTERVAL_MS);

    this.mutableVectors = new ConcurrentRandomAccessVectorValues(dimension);
    if (env != null && env.getRegion() != null && env.getRegion().getRegionInfo() != null) {
      byte[] sk = env.getRegion().getRegionInfo().getStartKey();
      this.regionStartKey = sk != null ? Arrays.copyOf(sk, sk.length) : HConstants.EMPTY_START_ROW;
      byte[] ek = env.getRegion().getRegionInfo().getEndKey();
      this.regionEndKey = ek != null ? Arrays.copyOf(ek, ek.length) : HConstants.EMPTY_END_ROW;
    } else {
      this.regionStartKey = HConstants.EMPTY_START_ROW;
      this.regionEndKey = HConstants.EMPTY_END_ROW;
    }
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
    this(tableName, regionName, conf, dimension, similarityFunction, m, efConstruction, alpha,
      segmentFamily, segmentQualifier, "NONE");
  }

  public HnswIndexManager(String tableName, byte[] regionName, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha,
    byte[] segmentFamily, byte[] segmentQualifier, String quantizationType) {
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
    this.quantizationType = quantizationType != null ? quantizationType : "NONE";
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
    this.regionStartKey = HConstants.EMPTY_START_ROW;
    this.regionEndKey = HConstants.EMPTY_END_ROW;
    // mutableBuilder and allocator are deferred to open() to avoid side effects
  }

  public HnswIndexManager(String tableName, byte[] regionName, byte[] regionStartKey,
    byte[] regionEndKey, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha,
    byte[] segmentFamily, byte[] segmentQualifier) {
    this(tableName, regionName, regionStartKey, regionEndKey, conf, dimension, similarityFunction,
      m, efConstruction, alpha, segmentFamily, segmentQualifier, "NONE");
  }

  public HnswIndexManager(String tableName, byte[] regionName, byte[] regionStartKey,
    byte[] regionEndKey, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha,
    byte[] segmentFamily, byte[] segmentQualifier, String quantizationType) {
    this(tableName, regionName, conf, dimension, similarityFunction, m, efConstruction, alpha,
      segmentFamily, segmentQualifier, quantizationType);
    this.regionStartKey = regionStartKey != null
      ? Arrays.copyOf(regionStartKey, regionStartKey.length)
      : HConstants.EMPTY_START_ROW;
    this.regionEndKey = regionEndKey != null
      ? Arrays.copyOf(regionEndKey, regionEndKey.length)
      : HConstants.EMPTY_END_ROW;
  }

  public HnswIndexManager(String tableName, byte[] regionName, byte[] regionStartKey,
    byte[] regionEndKey, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha) {
    this(tableName, regionName, regionStartKey, regionEndKey, conf, dimension, similarityFunction,
      m, efConstruction, alpha, DEFAULT_SEGMENT_FAMILY, DEFAULT_SEGMENT_QUALIFIER, "NONE");
  }

  public HnswIndexManager(String tableName, byte[] regionName, Configuration conf, int dimension,
    VectorSimilarityFunction similarityFunction, int m, int efConstruction, float alpha,
    byte[] segmentFamily, byte[] segmentQualifier, HnswOffheapAllocator allocator) {
    this(tableName, regionName, conf, dimension, similarityFunction, m, efConstruction, alpha,
      segmentFamily, segmentQualifier, "NONE");
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
   * Represents metadata for an immutable graph segment stored in
   * {@code SYSTEM.VECTOR_GRAPH_SEGMENT}.
   */
  public static class SegmentMetadata {
    private final String indexName;
    private final byte[] regionStartKey;
    private final long generationId;
    private final byte[] regionEndKey;
    private final String regionEncodedName;
    private final byte[] segmentRowKey;
    private final long nodeCount;
    private final long constructionTime;
    private final String rebuildState;

    public SegmentMetadata(String indexName, byte[] regionStartKey, long generationId,
      byte[] regionEndKey, String regionEncodedName, byte[] segmentRowKey, long nodeCount,
      long constructionTime, String rebuildState) {
      this.indexName = indexName;
      this.regionStartKey =
        regionStartKey != null ? Arrays.copyOf(regionStartKey, regionStartKey.length) : null;
      this.generationId = generationId;
      this.regionEndKey =
        regionEndKey != null ? Arrays.copyOf(regionEndKey, regionEndKey.length) : null;
      this.regionEncodedName = regionEncodedName;
      this.segmentRowKey =
        segmentRowKey != null ? Arrays.copyOf(segmentRowKey, segmentRowKey.length) : null;
      this.nodeCount = nodeCount;
      this.constructionTime = constructionTime;
      this.rebuildState = rebuildState;
    }

    public String getIndexName() {
      return indexName;
    }

    public byte[] getRegionStartKey() {
      return regionStartKey != null ? Arrays.copyOf(regionStartKey, regionStartKey.length) : null;
    }

    public long getGenerationId() {
      return generationId;
    }

    public byte[] getRegionEndKey() {
      return regionEndKey != null ? Arrays.copyOf(regionEndKey, regionEndKey.length) : null;
    }

    public String getRegionEncodedName() {
      return regionEncodedName;
    }

    public byte[] getSegmentRowKey() {
      return segmentRowKey != null ? Arrays.copyOf(segmentRowKey, segmentRowKey.length) : null;
    }

    public long getNodeCount() {
      return nodeCount;
    }

    public long getConstructionTime() {
      return constructionTime;
    }

    public String getRebuildState() {
      return rebuildState;
    }
  }

  public byte[] getRegionStartKey() {
    return regionStartKey != null
      ? Arrays.copyOf(regionStartKey, regionStartKey.length)
      : HConstants.EMPTY_START_ROW;
  }

  public byte[] getRegionEndKey() {
    return regionEndKey != null
      ? Arrays.copyOf(regionEndKey, regionEndKey.length)
      : HConstants.EMPTY_END_ROW;
  }

  public void setRegionBoundaries(byte[] regionStartKey, byte[] regionEndKey) {
    this.regionStartKey = regionStartKey != null
      ? Arrays.copyOf(regionStartKey, regionStartKey.length)
      : HConstants.EMPTY_START_ROW;
    this.regionEndKey = regionEndKey != null
      ? Arrays.copyOf(regionEndKey, regionEndKey.length)
      : HConstants.EMPTY_END_ROW;
  }

  public SegmentMetadata getActiveSegmentMetadata() {
    return activeSegmentMetadata;
  }

  public void setActiveSegmentMetadata(SegmentMetadata metadata) {
    this.activeSegmentMetadata = metadata;
  }

  @FunctionalInterface
  public interface ConnectionSupplier {
    Connection get() throws SQLException;
  }

  @FunctionalInterface
  public interface TableSupplier {
    Table get() throws Exception;
  }

  public void setConnectionSupplier(ConnectionSupplier supplier) {
    this.connectionSupplier = supplier;
  }

  public void setTableSupplier(TableSupplier supplier) {
    this.tableSupplier = supplier;
  }

  public void setBaseTableSupplier(TableSupplier supplier) {
    this.baseTableSupplier = supplier;
  }

  public TableSupplier getBaseTableSupplier() {
    return this.baseTableSupplier;
  }

  public void setRegion(Region region) {
    this.region = region;
  }

  public Region getRegion() {
    return this.region != null ? this.region : (env != null ? env.getRegion() : null);
  }

  public int getLastRecoveredRows() {
    return lastRecoveredRows;
  }

  public int getLastRecoveredUpserts() {
    return lastRecoveredUpserts;
  }

  public int getLastRecoveredDeletes() {
    return lastRecoveredDeletes;
  }

  public String getBaseTableName() {
    if (table != null) {
      if (table.getType() == PTableType.INDEX) {
        if (table.getParentName() != null) {
          return table.getParentName().getString();
        } else if (table.getParentTableName() != null) {
          return table.getParentTableName().getString();
        }
      }
      return table.getName().getString();
    }
    return tableName;
  }

  protected Table getBaseTable() throws IOException {
    if (baseTableSupplier != null) {
      try {
        return baseTableSupplier.get();
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
    if (env != null && env.getConnection() != null) {
      String baseName = getBaseTableName();
      if (baseName != null) {
        return env.getConnection().getTable(TableName.valueOf(baseName));
      }
    }
    return null;
  }

  protected Connection getPhoenixConnection() throws SQLException {
    if (connectionSupplier != null) {
      return connectionSupplier.get();
    }
    return QueryUtil.getConnectionOnServer(this.conf);
  }

  protected Table getHBaseTable() throws IOException {
    if (tableSupplier != null) {
      try {
        return tableSupplier.get();
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
    if (env != null && env.getConnection() != null) {
      TableName hbaseTableName = (table != null && table.getPhysicalName() != null)
        ? TableName.valueOf(table.getPhysicalName().getBytes())
        : TableName.valueOf(tableName);
      return env.getConnection().getTable(hbaseTableName);
    }
    return null;
  }

  /**
   * Queries {@code SYSTEM.VECTOR_GRAPH_SEGMENT} for the latest generation segment covering the
   * region's key range.
   */
  public SegmentMetadata findLatestCoveringSegment() throws IOException {
    if (this.tableName == null) {
      return null;
    }
    Connection conn = null;
    boolean closeConn = false;
    try {
      conn = getPhoenixConnection();
      if (conn == null) {
        return null;
      }
      closeConn = true;
      return queryLatestCoveringSegment(conn, this.tableName, this.regionStartKey,
        this.regionEndKey);
    } catch (Exception e) {
      LOG.warn("Failed to query SYSTEM.VECTOR_GRAPH_SEGMENT for table {} region {}: {}", tableName,
        Bytes.toStringBinary(regionName), e.getMessage());
      return null;
    } finally {
      if (closeConn && conn != null) {
        try {
          conn.close();
        } catch (Exception e) {
          LOG.debug("Error closing connection for {}: {}", tableName, e.getMessage());
        }
      }
    }
  }

  /**
   * Queries {@code SYSTEM.VECTOR_GRAPH_SEGMENT} using the provided connection for the latest
   * generation segment covering the specified region key range.
   */
  public static SegmentMetadata queryLatestCoveringSegment(Connection conn, String indexName,
    byte[] regionStartKey, byte[] regionEndKey) throws SQLException {
    Preconditions.checkNotNull(conn, "conn cannot be null");
    Preconditions.checkNotNull(indexName, "indexName cannot be null");

    // 1. Try exact start key match first
    boolean hasStartKey = regionStartKey != null && regionStartKey.length > 0;
    String exactSql = "SELECT " + PhoenixDatabaseMetaData.INDEX_NAME + ", "
      + PhoenixDatabaseMetaData.REGION_START_KEY + ", " + PhoenixDatabaseMetaData.GENERATION_ID
      + ", " + PhoenixDatabaseMetaData.REGION_END_KEY + ", "
      + PhoenixDatabaseMetaData.REGION_ENCODED_NAME + ", " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY
      + ", " + PhoenixDatabaseMetaData.NODE_COUNT + ", " + PhoenixDatabaseMetaData.CONSTRUCTION_TIME
      + ", " + PhoenixDatabaseMetaData.REBUILD_STATE + " FROM "
      + PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE "
      + PhoenixDatabaseMetaData.INDEX_NAME + " = ? AND " + PhoenixDatabaseMetaData.REGION_START_KEY
      + (hasStartKey ? " = ?" : " IS NULL") + " ORDER BY " + PhoenixDatabaseMetaData.GENERATION_ID
      + " DESC LIMIT 1";

    try (PreparedStatement ps = conn.prepareStatement(exactSql)) {
      ps.setString(1, indexName);
      if (hasStartKey) {
        ps.setBytes(2, regionStartKey);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return extractSegmentMetadata(rs);
        }
      }
    } catch (SQLException ex) {
      LOG.debug("Exact segment query failed for index {}: {}", indexName, ex.getMessage());
    }

    // 2. Fallback: Range discovery (for daughter regions after split or non-exact matches)
    String rangeSql = "SELECT " + PhoenixDatabaseMetaData.INDEX_NAME + ", "
      + PhoenixDatabaseMetaData.REGION_START_KEY + ", " + PhoenixDatabaseMetaData.GENERATION_ID
      + ", " + PhoenixDatabaseMetaData.REGION_END_KEY + ", "
      + PhoenixDatabaseMetaData.REGION_ENCODED_NAME + ", " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY
      + ", " + PhoenixDatabaseMetaData.NODE_COUNT + ", " + PhoenixDatabaseMetaData.CONSTRUCTION_TIME
      + ", " + PhoenixDatabaseMetaData.REBUILD_STATE + " FROM "
      + PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE "
      + PhoenixDatabaseMetaData.INDEX_NAME + " = ? " + " ORDER BY "
      + PhoenixDatabaseMetaData.GENERATION_ID + " DESC";

    try (PreparedStatement ps = conn.prepareStatement(rangeSql)) {
      ps.setString(1, indexName);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          SegmentMetadata meta = extractSegmentMetadata(rs);
          if (
            isCoveringOrOverlapping(meta.getRegionStartKey(), meta.getRegionEndKey(),
              regionStartKey, regionEndKey)
          ) {
            return meta;
          }
        }
      }
    } catch (SQLException ex) {
      LOG.debug("Range segment query failed for index {}: {}", indexName, ex.getMessage());
    }
    return null;
  }

  /**
   * Determines whether segment interval [segStart, segEnd) overlaps or covers region interval
   * [regStart, regEnd). In HBase, null or empty byte[] for start means -infinity; for end means
   * +infinity.
   */
  public static boolean isCoveringOrOverlapping(byte[] segStart, byte[] segEnd, byte[] regStart,
    byte[] regEnd) {
    boolean segStartBeforeRegEnd = (regEnd == null || regEnd.length == 0)
      || (segStart == null || segStart.length == 0) || (Bytes.compareTo(segStart, regEnd) < 0);

    boolean segEndAfterRegStart = (segEnd == null || segEnd.length == 0)
      || (regStart == null || regStart.length == 0) || (Bytes.compareTo(segEnd, regStart) > 0);

    return segStartBeforeRegEnd && segEndAfterRegStart;
  }

  private static SegmentMetadata extractSegmentMetadata(ResultSet rs) throws SQLException {
    String idxName = rs.getString(1);
    byte[] rStart = rs.getBytes(2);
    long genId = rs.getLong(3);
    byte[] rEnd = rs.getBytes(4);
    String encName = rs.getString(5);
    byte[] segRowKey = rs.getBytes(6);
    long nodeCount = rs.getLong(7);
    long constTime = rs.getLong(8);
    String rebuildState = rs.getString(9);
    return new SegmentMetadata(idxName, rStart, genId, rEnd, encName, segRowKey, nodeCount,
      constTime, rebuildState);
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
    loadLatestSegment();
    runCatchUpRecovery();
    if (this.flushIntervalMs > 0 && this.flushScheduler == null) {
      this.flushScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
        .setDaemon(true).setNameFormat("HnswFlush-" + tableName + "-%d").build());
      this.scheduledFlushTask = this.flushScheduler.scheduleWithFixedDelay(() -> {
        try {
          if (!closed && initialized) {
            flush();
          }
        } catch (Throwable t) {
          LOG.warn("Background flush failed for table {} region {}", tableName,
            Bytes.toStringBinary(regionName), t);
        }
      }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }
    this.initialized = true;
  }

  /**
   * Discovers and materializes the latest segment covering the region's key range into off-heap
   * direct memory via {@link HnswOffheapAllocator} and initializes {@link PhoenixMobReaderSupplier}
   * and {@link OnDiskGraphIndex}.
   */
  protected void loadLatestSegment() throws IOException {
    if (this.segmentBuffer != null) {
      return;
    }
    SegmentMetadata metadata = null;
    try {
      metadata = findLatestCoveringSegment();
    } catch (Exception e) {
      LOG.warn("Failed to find latest covering segment for table {} region {}: {}", tableName,
        Bytes.toStringBinary(regionName), e.getMessage());
    }
    if (metadata == null || metadata.getSegmentRowKey() == null) {
      LOG.debug("No active segment found in SYSTEM.VECTOR_GRAPH_SEGMENT for table {} region {}",
        tableName, Bytes.toStringBinary(regionName));
      return;
    }
    this.activeSegmentMetadata = metadata;
    byte[] segRowKey = metadata.getSegmentRowKey();
    LOG.info("Loading segment {} (gen={}, nodes={}) for table {} region {}",
      Bytes.toStringBinary(segRowKey), metadata.getGenerationId(), metadata.getNodeCount(),
      tableName, Bytes.toStringBinary(regionName));

    Table hTable = null;
    try {
      hTable = getHBaseTable();
      if (hTable != null) {
        loadSegment(hTable, segRowKey);
        return;
      }
    } catch (Exception e) {
      LOG.warn("Failed to load segment {} via HBase Table for {}: {}",
        Bytes.toStringBinary(segRowKey), tableName, e.getMessage());
    } finally {
      if (hTable != null && tableSupplier == null) {
        try {
          hTable.close();
        } catch (Exception e) {
          LOG.debug("Error closing table for {}: {}", tableName, e.getMessage());
        }
      }
    }

    if (env != null && env.getRegion() != null) {
      try {
        loadSegment(env.getRegion(), segRowKey);
      } catch (Exception e) {
        LOG.warn("Failed to load segment {} via Region for {}: {}", Bytes.toStringBinary(segRowKey),
          tableName, e.getMessage());
      }
    }
  }

  /**
   * Crash recovery catch-up scan to replay mutations committed after segment construction.
   * <p>
   * Bounded to mutations occurring in {@code [constructionTime, Long.MAX_VALUE)} on the base table.
   * Replays inserts/updates into {@link #mutableBuilder} and applies deletes so un-flushed vector
   * updates are recovered following an unexpected RegionServer restart.
   */
  public void runCatchUpRecovery() throws IOException {
    long constructionTime = 0L;
    if (activeSegmentMetadata != null && activeSegmentMetadata.getConstructionTime() > 0) {
      constructionTime = activeSegmentMetadata.getConstructionTime();
    } else {
      try {
        SegmentMetadata latest = findLatestCoveringSegment();
        if (latest != null && latest.getConstructionTime() > 0) {
          constructionTime = latest.getConstructionTime();
          this.activeSegmentMetadata = latest;
        }
      } catch (Exception e) {
        LOG.debug("Could not determine constructionTime from segment metadata for table {}: {}",
          tableName, e.getMessage());
      }
    }

    LOG.info("Running catch-up recovery scan for table {} region {} with constructionTime={}",
      tableName, Bytes.toStringBinary(regionName), constructionTime);

    Scan scan = new Scan();
    if (constructionTime > 0) {
      scan.setTimeRange(constructionTime, HConstants.LATEST_TIMESTAMP);
    }
    scan.setRaw(true);
    scan.readAllVersions();
    scan.setCacheBlocks(false);

    lastRecoveredRows = 0;
    lastRecoveredUpserts = 0;
    lastRecoveredDeletes = 0;

    Region currentRegion = getRegion();
    if (currentRegion != null) {
      runCatchUpScanOnRegion(currentRegion, scan);
      LOG.info(
        "Catch-up recovery completed via Region for table {} region {}: scanned {} rows, "
          + "replayed {} upserts, {} deletes",
        tableName, Bytes.toStringBinary(regionName), lastRecoveredRows, lastRecoveredUpserts,
        lastRecoveredDeletes);
      return;
    }

    Table baseTable = null;
    try {
      baseTable = getBaseTable();
      if (baseTable != null) {
        if (regionStartKey != null && regionStartKey.length > 0) {
          scan.withStartRow(regionStartKey);
        }
        if (regionEndKey != null && regionEndKey.length > 0) {
          scan.withStopRow(regionEndKey);
        }
        runCatchUpScanOnTable(baseTable, scan);
        LOG.info(
          "Catch-up recovery completed via Table for table {} region {}: scanned {} rows, "
            + "replayed {} upserts, {} deletes",
          tableName, Bytes.toStringBinary(regionName), lastRecoveredRows, lastRecoveredUpserts,
          lastRecoveredDeletes);
      } else {
        LOG.debug(
          "Neither Region nor Base Table available for catch-up recovery scan on table {} region {}",
          tableName, Bytes.toStringBinary(regionName));
      }
    } finally {
      if (baseTable != null && baseTableSupplier == null) {
        try {
          baseTable.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private void runCatchUpScanOnRegion(Region region, Scan scan) throws IOException {
    RegionScanner scanner = region.getScanner(scan);
    if (scanner == null) {
      return;
    }
    try (RegionScanner rs = scanner) {
      List<Cell> cells = new ArrayList<>();
      boolean hasMore;
      do {
        cells.clear();
        try {
          hasMore = rs.nextRaw(cells);
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
          hasMore = rs.next(cells);
        }
        if (!cells.isEmpty()) {
          lastRecoveredRows++;
          replayMutationCells(cells);
        }
      } while (hasMore);
    }
  }

  private void runCatchUpScanOnTable(Table table, Scan scan) throws IOException {
    ResultScanner scanner = table.getScanner(scan);
    if (scanner == null) {
      return;
    }
    try (ResultScanner rs = scanner) {
      for (Result result : rs) {
        if (result != null && !result.isEmpty()) {
          lastRecoveredRows++;
          replayMutationCells(result.listCells());
        }
      }
    }
  }

  protected void replayMutationCells(List<Cell> cells) {
    if (cells == null || cells.isEmpty()) {
      return;
    }
    byte[] rowKey = CellUtil.cloneRow(cells.get(0));

    // Group cells for this row by timestamp in chronological order (ascending)
    TreeMap<Long, List<Cell>> mutationsByTimestamp = new TreeMap<>();
    for (Cell cell : cells) {
      mutationsByTimestamp.computeIfAbsent(cell.getTimestamp(), k -> new ArrayList<>()).add(cell);
    }

    for (Map.Entry<Long, List<Cell>> entry : mutationsByTimestamp.entrySet()) {
      List<Cell> mutationCells = entry.getValue();
      VectorFloat<?> vector = extractVectorFromRowCells(rowKey, mutationCells);
      if (vector != null) {
        upsert(rowKey, vector);
        lastRecoveredUpserts++;
      } else if (isVectorDeletedInCells(mutationCells)) {
        delete(rowKey);
        lastRecoveredDeletes++;
      }
    }
  }

  protected VectorFloat<?> extractVectorFromRowCells(byte[] rowKey, List<Cell> cells) {
    if (cells == null || cells.isEmpty()) {
      return null;
    }

    // 1. Direct column lookup using table metadata if available
    if (table != null) {
      try {
        PColumn vectorCol = IndexUtil.findVectorColumn(table);
        if (vectorCol != null) {
          byte[] family =
            vectorCol.getFamilyName() != null ? vectorCol.getFamilyName().getBytes() : null;
          byte[] qualifier = vectorCol.getName().getBytes();
          String qualStr = vectorCol.getName().getString();
          int colonIdx = qualStr.indexOf(':');
          byte[] strippedQual =
            colonIdx >= 0 ? Bytes.toBytes(qualStr.substring(colonIdx + 1)) : qualifier;

          for (Cell cell : cells) {
            if (CellUtil.isDelete(cell)) {
              continue;
            }
            boolean matchQual = Bytes.equals(cell.getQualifierArray(), cell.getQualifierOffset(),
              cell.getQualifierLength(), qualifier, 0, qualifier.length)
              || Bytes.equals(cell.getQualifierArray(), cell.getQualifierOffset(),
                cell.getQualifierLength(), strippedQual, 0, strippedQual.length);
            boolean matchFam = family == null || Bytes.equals(cell.getFamilyArray(),
              cell.getFamilyOffset(), cell.getFamilyLength(), family, 0, family.length);
            if (matchQual && matchFam) {
              boolean isDouble = vectorCol.getDataType() instanceof PVectorDouble;
              SortOrder sortOrder = vectorCol.getSortOrder();
              return decodeVector(cell.getValueArray(), cell.getValueOffset(),
                cell.getValueLength(), isDouble, sortOrder);
            }
          }
        }
      } catch (Exception e) {
        LOG.debug("Could not extract vector from table schema for table {}: {}", tableName,
          e.getMessage());
      }
    }

    // 2. Fallback: inspect cells by expected byte length
    int expectedFloatBytes = dimension * Bytes.SIZEOF_FLOAT;
    int expectedDoubleBytes = dimension * Bytes.SIZEOF_DOUBLE;
    for (Cell cell : cells) {
      if (CellUtil.isDelete(cell)) {
        continue;
      }
      int len = cell.getValueLength();
      if (dimension > 0 && len == expectedFloatBytes) {
        return decodeVector(cell.getValueArray(), cell.getValueOffset(), len, false, SortOrder.ASC);
      } else if (dimension > 0 && len == expectedDoubleBytes) {
        return decodeVector(cell.getValueArray(), cell.getValueOffset(), len, true, SortOrder.ASC);
      }
    }

    return null;
  }

  private boolean isVectorDeletedInCells(List<Cell> cells) {
    if (cells == null || cells.isEmpty()) {
      return false;
    }
    byte[] qualifier = null;
    byte[] strippedQual = null;
    byte[] family = null;
    if (table != null) {
      try {
        PColumn vectorCol = IndexUtil.findVectorColumn(table);
        if (vectorCol != null) {
          qualifier = vectorCol.getName().getBytes();
          family = vectorCol.getFamilyName() != null ? vectorCol.getFamilyName().getBytes() : null;
          String qualStr = vectorCol.getName().getString();
          int colonIdx = qualStr.indexOf(':');
          strippedQual = colonIdx >= 0 ? Bytes.toBytes(qualStr.substring(colonIdx + 1)) : qualifier;
        }
      } catch (Exception ignored) {
      }
    }

    for (Cell cell : cells) {
      if (CellUtil.isDeleteFamily(cell) || CellUtil.isDeleteFamilyVersion(cell)) {
        return true;
      }
      if (CellUtil.isDelete(cell)) {
        if (qualifier != null) {
          boolean matchQual = Bytes.equals(cell.getQualifierArray(), cell.getQualifierOffset(),
            cell.getQualifierLength(), qualifier, 0, qualifier.length)
            || (strippedQual != null
              && Bytes.equals(cell.getQualifierArray(), cell.getQualifierOffset(),
                cell.getQualifierLength(), strippedQual, 0, strippedQual.length));
          boolean matchFam = family == null || Bytes.equals(cell.getFamilyArray(),
            cell.getFamilyOffset(), cell.getFamilyLength(), family, 0, family.length);
          if (matchQual && matchFam) {
            return true;
          }
        } else {
          return true;
        }
      }
    }
    return false;
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
    if (directBuffer == null) {
      throw new IOException("Segment buffer cannot be null");
    }
    closeActiveSegment();
    ByteBuffer graphBuffer = directBuffer;
    Map<Integer, byte[]> mapping = null;
    if (directBuffer.limit() >= 8) {
      int magic = directBuffer.getInt(directBuffer.limit() - 4);
      if (magic == ORDINAL_MAPPING_MAGIC) {
        Pair<ByteBuffer, Map<Integer, byte[]>> split = splitSegmentAndMapping(directBuffer);
        graphBuffer = split.getFirst();
        mapping = split.getSecond();
      }
    }
    this.segmentBuffer = graphBuffer;
    this.readerSupplier = new PhoenixMobReaderSupplier(graphBuffer);
    this.onDiskGraphIndex = OnDiskGraphIndex.load(readerSupplier);
    if (mapping != null) {
      populateOrdinalMapping(mapping);
    } else {
      ordinalToKey.clear();
      keyToOrdinal.clear();
      nextOrdinal.set(onDiskGraphIndex.size());
    }
    // Reloading the segment baseline drops the mapping of any mutable node, so none is live now.
    liveMutableOrdinals.clear();
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

  /** Returns the column family used for segment cell storage. */
  public byte[] getSegmentFamily() {
    return Arrays.copyOf(segmentFamily, segmentFamily.length);
  }

  /** Returns the column qualifier used for segment cell storage. */
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

  /** Creates a standard HBase {@link Put} mutation for storing a serialized graph segment. */
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

  /** Writes a serialized graph segment to an HBase {@link Region} via standard {@link Put}. */
  public void writeSegment(Region region, byte[] segmentRowKey, byte[] segmentPayload)
    throws IOException {
    checkNotClosed();
    Preconditions.checkNotNull(region, "region cannot be null");
    Put put = createSegmentPut(segmentRowKey, segmentPayload);
    region.put(put);
  }

  /** Writes a serialized graph segment to an HBase {@link Region} via standard {@link Put}. */
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
    this.segmentLoader = () -> {
      Table t = getHBaseTable();
      if (t != null) {
        try {
          return readSegmentPayload(t, activeSegmentRowKey, family, qualifier);
        } finally {
          if (tableSupplier == null) {
            try {
              t.close();
            } catch (Exception e) {
              LOG.debug("Error closing table for {}: {}", tableName, e.getMessage());
            }
          }
        }
      }
      return readSegmentPayload(table, activeSegmentRowKey, family, qualifier);
    };
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
    if (mutableBuilder == null) {
      mutableBuilder = createGraphIndexBuilder();
    }

    ImmutableBytesPtr keyPtr = new ImmutableBytesPtr(rowKey);
    Integer existingOrdinal = keyToOrdinal.get(keyPtr);

    int newOrdinal = nextOrdinal.getAndIncrement();
    mutableVectors.putVector(newOrdinal, vector);
    byte[] keyCopy = Arrays.copyOf(rowKey, rowKey.length);
    ordinalToKey.put(newOrdinal, keyCopy);
    keyToOrdinal.put(new ImmutableBytesPtr(keyCopy), newOrdinal);

    mutableBuilder.addGraphNode(newOrdinal, vector);
    liveMutableOrdinals.add(newOrdinal);

    if (existingOrdinal != null) {
      mutableBuilder.markNodeDeleted(existingOrdinal);
      ordinalToKey.remove(existingOrdinal);
      liveMutableOrdinals.remove(existingOrdinal);
    }

    if (flushThreshold > 0 && getMutableNodeCount() >= flushThreshold) {
      triggerFlushAsync();
    }
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
    if (mutableBuilder == null) {
      mutableBuilder = createGraphIndexBuilder();
    }

    ImmutableBytesPtr keyPtr = new ImmutableBytesPtr(rowKey);
    Integer ordinal = keyToOrdinal.remove(keyPtr);
    if (ordinal != null) {
      ordinalToKey.remove(ordinal);
      mutableBuilder.markNodeDeleted(ordinal);
      if (liveMutableOrdinals.remove(ordinal)) {
        resetMutableGraphIfFullyDeleted();
      }
    }
  }

  /**
   * Discards the mutable graph once every node it holds has been marked deleted.
   * <p>
   * JVector leaves a deleted node in place, entry point included, until
   * {@link GraphIndexBuilder#removeDeletedNodes()} runs at flush time. Node insertion only links a
   * new node to nodes the entry point search accepts, and acceptance excludes deleted nodes, so a
   * node added while no live node is reachable from the entry point acquires no neighbors. Nor does
   * it take over as entry point, since that only happens for a strictly higher level. It would
   * therefore stay unreachable from {@link #searchMutable} until the next flush. A fully deleted
   * graph holds nothing retrievable, so dropping it lets the next upsert establish a fresh entry
   * point. Deletions stay recorded in {@link #ordinalToKey}, which is what flush prunes against.
   */
  private void resetMutableGraphIfFullyDeleted() {
    if (!liveMutableOrdinals.isEmpty() || getMutableNodeCount() == 0) {
      return;
    }
    LOG.debug("Resetting fully deleted mutable HNSW graph ({} nodes) for table {} region {}",
      getMutableNodeCount(), tableName, Bytes.toStringBinary(regionName));
    GraphIndexBuilder discarded = mutableBuilder;
    mutableVectors = new ConcurrentRandomAccessVectorValues(dimension);
    mutableBuilder = createGraphIndexBuilder();
    if (discarded != null) {
      try {
        discarded.close();
      } catch (Exception e) {
        LOG.warn("Error closing discarded mutable HNSW graph builder for table {}", tableName, e);
      }
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

  /** Returns the next ordinal to be assigned to a new vector node. */
  public int getNextOrdinal() {
    return nextOrdinal.get();
  }

  /**
   * Serializes an ordinal-to-primary-key mapping into a binary format:
   * {@code [count (4B)][ordinal (4B), keyLen (4B), key (keyLen B)]*}.
   */
  public static byte[] serializeOrdinalMapping(Map<Integer, byte[]> ordinalToKey)
    throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    if (ordinalToKey == null || ordinalToKey.isEmpty()) {
      dos.writeInt(0);
    } else {
      dos.writeInt(ordinalToKey.size());
      for (Map.Entry<Integer, byte[]> entry : ordinalToKey.entrySet()) {
        dos.writeInt(entry.getKey());
        byte[] key = entry.getValue();
        if (key != null) {
          dos.writeInt(key.length);
          dos.write(key);
        } else {
          dos.writeInt(0);
        }
      }
    }
    dos.flush();
    return baos.toByteArray();
  }

  /** Deserializes an ordinal-to-primary-key mapping from binary format. */
  public static Map<Integer, byte[]> deserializeOrdinalMapping(byte[] data) throws IOException {
    if (data == null || data.length < 4) {
      throw new IOException("Invalid ordinal mapping data: buffer too small");
    }
    ByteArrayInputStream bais = new ByteArrayInputStream(data);
    DataInputStream dis = new DataInputStream(bais);
    int count = dis.readInt();
    if (count < 0) {
      throw new IOException("Invalid ordinal mapping count: " + count);
    }
    Map<Integer, byte[]> mapping = new HashMap<>(count);
    for (int i = 0; i < count; i++) {
      int ordinal = dis.readInt();
      int keyLen = dis.readInt();
      if (keyLen < 0 || keyLen > dis.available()) {
        throw new IOException("Invalid key length in ordinal mapping: " + keyLen);
      }
      byte[] key = new byte[keyLen];
      dis.readFully(key);
      mapping.put(ordinal, key);
    }
    return mapping;
  }

  /**
   * Combines raw graph bytes and mapping bytes into a single payload with a trailer:
   * {@code [graph bytes][mapping bytes][mapping length (4B)][magic (4B)]}.
   */
  public static byte[] combineSegmentAndMapping(byte[] graphBytes, byte[] mappingBytes) {
    int gLen = (graphBytes != null) ? graphBytes.length : 0;
    int mLen = (mappingBytes != null) ? mappingBytes.length : 0;
    ByteBuffer combined = ByteBuffer.allocate(gLen + mLen + 8);
    if (graphBytes != null && gLen > 0) {
      combined.put(graphBytes);
    }
    if (mappingBytes != null && mLen > 0) {
      combined.put(mappingBytes);
    }
    combined.putInt(mLen);
    combined.putInt(ORDINAL_MAPPING_MAGIC);
    return combined.array();
  }

  /**
   * Splits a combined byte array into raw graph bytes and the deserialized ordinal-to-PK mapping.
   * Reads the trailing 8 bytes to locate the magic and mapping length.
   */
  public static Pair<byte[], Map<Integer, byte[]>> splitSegmentAndMapping(byte[] combined)
    throws IOException {
    if (combined == null || combined.length < 8) {
      throw new IOException(
        "Combined segment buffer too small: " + (combined == null ? 0 : combined.length));
    }
    int magic = Bytes.toInt(combined, combined.length - 4);
    if (magic != ORDINAL_MAPPING_MAGIC) {
      throw new IOException(String.format("Invalid ordinal mapping magic: 0x%08X (expected 0x%08X)",
        magic, ORDINAL_MAPPING_MAGIC));
    }
    int mappingLength = Bytes.toInt(combined, combined.length - 8);
    if (mappingLength < 0 || mappingLength > combined.length - 8) {
      throw new IOException(
        String.format("Invalid ordinal mapping length: %d (total combined length: %d)",
          mappingLength, combined.length));
    }
    int graphLength = combined.length - 8 - mappingLength;
    byte[] graphBytes = Arrays.copyOfRange(combined, 0, graphLength);
    byte[] mappingBytes = Arrays.copyOfRange(combined, graphLength, graphLength + mappingLength);
    Map<Integer, byte[]> mapping = deserializeOrdinalMapping(mappingBytes);
    return new Pair<>(graphBytes, mapping);
  }

  /**
   * Splits a ByteBuffer containing a combined segment and trailer into a sliced graph ByteBuffer
   * and the deserialized ordinal-to-PK mapping.
   */
  public static Pair<ByteBuffer, Map<Integer, byte[]>> splitSegmentAndMapping(ByteBuffer buffer)
    throws IOException {
    if (buffer == null || buffer.limit() < 8) {
      throw new IOException("Buffer too small for ordinal mapping trailer");
    }
    int limit = buffer.limit();
    int magic = buffer.getInt(limit - 4);
    if (magic != ORDINAL_MAPPING_MAGIC) {
      throw new IOException(String.format("Invalid ordinal mapping magic: 0x%08X (expected 0x%08X)",
        magic, ORDINAL_MAPPING_MAGIC));
    }
    int mappingLength = buffer.getInt(limit - 8);
    if (mappingLength < 0 || mappingLength > limit - 8) {
      throw new IOException(String.format("Invalid ordinal mapping length: %d (buffer limit: %d)",
        mappingLength, limit));
    }
    int graphLength = limit - 8 - mappingLength;
    byte[] mappingBytes = new byte[mappingLength];
    ByteBuffer dup = buffer.duplicate();
    dup.position(graphLength);
    dup.get(mappingBytes);
    Map<Integer, byte[]> mapping = deserializeOrdinalMapping(mappingBytes);

    ByteBuffer graphSlice = buffer.duplicate();
    graphSlice.position(0);
    graphSlice.limit(graphLength);
    return new Pair<>(graphSlice.slice(), mapping);
  }

  private void populateOrdinalMapping(Map<Integer, byte[]> mapping) {
    ordinalToKey.clear();
    keyToOrdinal.clear();
    int maxOrdinal = -1;
    if (mapping != null) {
      for (Map.Entry<Integer, byte[]> entry : mapping.entrySet()) {
        int ordinal = entry.getKey();
        byte[] key = entry.getValue();
        ordinalToKey.put(ordinal, key);
        keyToOrdinal.put(new ImmutableBytesPtr(key), ordinal);
        if (ordinal > maxOrdinal) {
          maxOrdinal = ordinal;
        }
      }
    }
    nextOrdinal.set(maxOrdinal + 1);
  }

  /**
   * Searches the immutable on-disk graph segment for approximate nearest neighbors. If the
   * immutable segment was previously evicted by {@link HnswOffheapAllocator}, it is re-materialized
   * on demand from MOB storage before executing the search.
   */
  /**
   * Searches the immutable on-disk graph segment for approximate nearest neighbors. If the
   * immutable segment was previously evicted by {@link HnswOffheapAllocator}, it is re-materialized
   * on demand from MOB storage before executing the search.
   */
  public SearchResult search(VectorFloat<?> queryVector, int topK) throws IOException {
    return search(queryVector, topK, 0);
  }

  /**
   * Searches the immutable on-disk graph segment and mutable buffers for approximate nearest
   * neighbors with specified efSearch parameter. During flush, candidate sets from the on-disk
   * segment, the snapshot mutable buffer, and the active mutable buffer are merged.
   */
  public SearchResult search(VectorFloat<?> queryVector, int topK, int efSearch)
    throws IOException {
    checkNotClosed();
    ensureSegmentLoaded();

    SearchResult onDiskResult = null;
    if (onDiskGraphIndex != null) {
      if (allocator != null && activeSegmentRowKey != null) {
        allocator.recordAccess(getSegmentKey());
      }
      try (GraphSearcher searcher = new GraphSearcher(onDiskGraphIndex)) {
        OnDiskGraphIndex.View view = (OnDiskGraphIndex.View) searcher.getView();
        SearchScoreProvider ssp;
        if (onDiskGraphIndex.getFeatureSet().contains(FeatureId.FUSED_PQ)) {
          FusedPQ fusedPq = (FusedPQ) onDiskGraphIndex.getFeatures().get(FeatureId.FUSED_PQ);
          ScoreFunction.ExactScoreFunction reranker =
            onDiskGraphIndex.getFeatureSet().contains(FeatureId.NVQ_VECTORS)
              ? view.rerankerFor(queryVector, similarityFunction)
              : null;
          ScoreFunction.ApproximateScoreFunction asf =
            fusedPq.approximateScoreFunctionFor(queryVector, similarityFunction, view, reranker);
          ssp = reranker != null
            ? new DefaultSearchScoreProvider(asf, reranker)
            : new DefaultSearchScoreProvider(asf);
        } else if (onDiskGraphIndex.getFeatureSet().contains(FeatureId.NVQ_VECTORS)) {
          ScoreFunction.ExactScoreFunction reranker =
            view.rerankerFor(queryVector, similarityFunction);
          ssp = new DefaultSearchScoreProvider(reranker);
        } else {
          ssp = DefaultSearchScoreProvider.exact(queryVector, similarityFunction, view);
        }
        if (efSearch > 0) {
          onDiskResult = searcher.search(ssp, topK, efSearch, 0.0f, 0.0f, Bits.ALL);
        } else {
          onDiskResult = searcher.search(ssp, topK, Bits.ALL);
        }
      }
    }

    GraphIndexBuilder snapBuilder = flushingSnapshotBuilder;
    ConcurrentRandomAccessVectorValues snapVectors = flushingSnapshotVectors;
    Map<Integer, byte[]> snapMapping = flushingSnapshotOrdinalToKey;
    SearchResult snapResult = null;
    if (snapBuilder != null && snapVectors != null && snapVectors.size() > 0) {
      snapResult = GraphSearcher.search(queryVector, topK, snapVectors, similarityFunction,
        snapBuilder.getGraph(), Bits.ALL);
    }

    SearchResult mutResult = null;
    if (mutableBuilder != null && mutableVectors.size() > 0 && !ordinalToKey.isEmpty()) {
      mutResult = searchMutable(queryVector, topK);
    }

    if (onDiskResult == null && snapResult == null && mutResult == null) {
      return null;
    }
    if (snapResult == null && mutResult == null && ordinalToKey.isEmpty()) {
      return onDiskResult;
    }

    return mergeSearchResults(topK, onDiskResult, snapResult, snapMapping, mutResult);
  }

  private SearchResult mergeSearchResults(int topK, SearchResult onDiskResult,
    SearchResult snapResult, Map<Integer, byte[]> snapMapping, SearchResult mutResult) {
    Map<ImmutableBytesPtr, SearchResult.NodeScore> candidates = new HashMap<>();

    int totalVisited = 0;
    int totalExpanded = 0;
    int totalExpandedBase = 0;
    int totalReranked = 0;

    // 1. Mutable buffer results (highest precedence)
    if (mutResult != null) {
      totalVisited += mutResult.getVisitedCount();
      totalExpanded += mutResult.getExpandedCount();
      totalExpandedBase += mutResult.getExpandedCountBaseLayer();
      totalReranked += mutResult.getRerankedCount();
      for (SearchResult.NodeScore ns : mutResult.getNodes()) {
        byte[] pk = getRowKeyForOrdinal(ns.node);
        if (pk != null && Objects.equals(getOrdinalForRowKey(pk), ns.node)) {
          candidates.put(new ImmutableBytesPtr(pk), ns);
        }
      }
    }

    // 2. Snapshot buffer results (during flush)
    if (snapResult != null) {
      totalVisited += snapResult.getVisitedCount();
      totalExpanded += snapResult.getExpandedCount();
      totalExpandedBase += snapResult.getExpandedCountBaseLayer();
      totalReranked += snapResult.getRerankedCount();
      for (SearchResult.NodeScore ns : snapResult.getNodes()) {
        byte[] pk = (snapMapping != null) ? snapMapping.get(ns.node) : getRowKeyForOrdinal(ns.node);
        if (pk != null && !candidates.containsKey(new ImmutableBytesPtr(pk))) {
          Integer currentOrd = getOrdinalForRowKey(pk);
          if (currentOrd != null) {
            candidates.put(new ImmutableBytesPtr(pk), ns);
          }
        }
      }
    }

    // 3. On-disk segment results
    if (onDiskResult != null) {
      totalVisited += onDiskResult.getVisitedCount();
      totalExpanded += onDiskResult.getExpandedCount();
      totalExpandedBase += onDiskResult.getExpandedCountBaseLayer();
      totalReranked += onDiskResult.getRerankedCount();
      for (SearchResult.NodeScore ns : onDiskResult.getNodes()) {
        byte[] pk = getRowKeyForOrdinal(ns.node);
        if (pk != null && !candidates.containsKey(new ImmutableBytesPtr(pk))) {
          Integer currentOrd = getOrdinalForRowKey(pk);
          if (currentOrd != null && currentOrd.equals(ns.node)) {
            candidates.put(new ImmutableBytesPtr(pk), ns);
          }
        }
      }
    }

    if (candidates.isEmpty()) {
      if (onDiskResult != null && mutResult == null && snapResult == null) {
        return onDiskResult;
      }
      return new SearchResult(new SearchResult.NodeScore[0], totalVisited, totalExpanded,
        totalExpandedBase, totalReranked, Float.POSITIVE_INFINITY);
    }

    List<SearchResult.NodeScore> sorted = new ArrayList<>(candidates.values());
    sorted.sort((a, b) -> Float.compare(b.score, a.score));

    int count = Math.min(topK, sorted.size());
    SearchResult.NodeScore[] topNodes = new SearchResult.NodeScore[count];
    for (int i = 0; i < count; i++) {
      topNodes[i] = sorted.get(i);
    }
    float worstScore = count > 0 ? topNodes[count - 1].score : Float.POSITIVE_INFINITY;
    return new SearchResult(topNodes, totalVisited, totalExpanded, totalExpandedBase, totalReranked,
      worstScore);
  }

  /** Searches the in-memory mutable graph index for approximate nearest neighbors. */
  public SearchResult searchMutable(VectorFloat<?> queryVector, int topK) {
    checkNotClosed();
    if (mutableBuilder == null || ordinalToKey.isEmpty()) {
      return new SearchResult(new SearchResult.NodeScore[0], 0, 0, 0, 0, Float.POSITIVE_INFINITY);
    }
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
    if (isVectorUnchanged) {
      return;
    }
    try {
      if (nextDataRowState != null) {
        byte[] rowKey = nextDataRowState.getRow();
        if (
          currentDataRowState != null && Bytes.compareTo(currentDataRowState.getRow(), rowKey) != 0
        ) {
          delete(currentDataRowState.getRow());
        }
        VectorFloat<?> vector =
          extractVector(nextDataRowState, nextDataRowVG, indexMaintainer, indexPut, ts);
        if (vector != null) {
          upsert(rowKey, vector);
        } else {
          delete(rowKey);
        }
      } else if (currentDataRowState != null) {
        delete(currentDataRowState.getRow());
      }
    } catch (Throwable t) {
      LOG.warn("Failed to process incremental mutation for HNSW index table {}", tableName, t);
    }
  }

  /**
   * Extracts a {@link VectorFloat} representation of the vector column from mutation state.
   */
  private VectorFloat<?> extractVector(Put nextDataRowState, ValueGetter nextDataRowVG,
    IndexMaintainer indexMaintainer, Put indexPut, long ts) {
    // 1. Primary path: evaluate via IndexMaintainer and ValueGetter
    if (indexMaintainer != null) {
      try {
        ValueGetter vg = nextDataRowVG != null
          ? nextDataRowVG
          : (nextDataRowState != null ? new IndexUtil.SimpleValueGetter(nextDataRowState) : null);
        if (vg != null) {
          ImmutableBytesWritable ptr = indexMaintainer.getVectorValue(vg, ts);
          if (ptr != null && ptr.get() != null && ptr.getLength() > 0) {
            SortOrder sortOrder = indexMaintainer.getVectorSortOrder();
            boolean isDouble = indexMaintainer.isDoubleVector((ColumnReference) null);
            return decodeVector(ptr.get(), ptr.getOffset(), ptr.getLength(), isDouble, sortOrder);
          }
        }
      } catch (Exception e) {
        LOG.debug("Could not evaluate vector expression via IndexMaintainer for table {}: {}",
          tableName, e.getMessage());
      }
    }

    // 2. Direct column lookup on nextDataRowState using table metadata
    if (nextDataRowState != null) {
      if (table != null) {
        try {
          PColumn vectorCol = IndexUtil.findVectorColumn(table);
          if (vectorCol != null) {
            byte[] family =
              vectorCol.getFamilyName() != null ? vectorCol.getFamilyName().getBytes() : null;
            byte[] qualifier = vectorCol.getName().getBytes();
            if (family != null) {
              List<Cell> cells = nextDataRowState.get(family, qualifier);
              if (cells != null && !cells.isEmpty()) {
                Cell cell = cells.get(0);
                boolean isDouble = vectorCol.getDataType() instanceof PVectorDouble;
                SortOrder sortOrder = vectorCol.getSortOrder();
                return decodeVector(cell.getValueArray(), cell.getValueOffset(),
                  cell.getValueLength(), isDouble, sortOrder);
              }
            }
          }
        } catch (Exception e) {
          LOG.debug("Could not extract vector from table schema for table {}: {}", tableName,
            e.getMessage());
        }
      }

      // 3. Fallback: inspect cells in nextDataRowState
      VectorFloat<?> vec = extractVectorFromCells(nextDataRowState.getFamilyCellMap());
      if (vec != null) {
        return vec;
      }
    }

    // 4. Fallback: inspect cells in indexPut
    if (indexPut != null) {
      VectorFloat<?> vec = extractVectorFromCells(indexPut.getFamilyCellMap());
      if (vec != null) {
        return vec;
      }
    }

    return null;
  }

  private VectorFloat<?> decodeVector(byte[] bytes, int offset, int length, boolean isDouble,
    SortOrder sortOrder) {
    if (bytes == null || length <= 0) {
      return null;
    }
    if (sortOrder == null) {
      sortOrder = SortOrder.ASC;
    }
    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    if (isDouble) {
      double[] doubles = (double[]) PVectorDouble.INSTANCE.toObject(bytes, offset, length,
        PVectorDouble.INSTANCE, sortOrder);
      if (doubles == null) {
        return null;
      }
      float[] floats = new float[doubles.length];
      for (int i = 0; i < doubles.length; i++) {
        floats[i] = (float) doubles[i];
      }
      return vts.createFloatVector(floats);
    } else {
      float[] floats = (float[]) PVectorFloat.INSTANCE.toObject(bytes, offset, length,
        PVectorFloat.INSTANCE, sortOrder);
      if (floats == null) {
        return null;
      }
      return vts.createFloatVector(floats);
    }
  }

  private VectorFloat<?> extractVectorFromCells(Map<byte[], List<Cell>> familyCellMap) {
    if (familyCellMap == null || familyCellMap.isEmpty()) {
      return null;
    }
    int expectedFloatBytes = dimension * Bytes.SIZEOF_FLOAT;
    int expectedDoubleBytes = dimension * Bytes.SIZEOF_DOUBLE;

    // First pass: match exact expected dimension byte length
    for (List<Cell> cells : familyCellMap.values()) {
      if (cells == null) {
        continue;
      }
      for (Cell cell : cells) {
        int len = cell.getValueLength();
        if (dimension > 0 && len == expectedFloatBytes) {
          return decodeVector(cell.getValueArray(), cell.getValueOffset(), len, false,
            SortOrder.ASC);
        } else if (dimension > 0 && len == expectedDoubleBytes) {
          return decodeVector(cell.getValueArray(), cell.getValueOffset(), len, true,
            SortOrder.ASC);
        }
      }
    }

    // Second pass: single cell with vector byte length
    int totalCells = 0;
    Cell singleCell = null;
    for (List<Cell> cells : familyCellMap.values()) {
      if (cells != null) {
        totalCells += cells.size();
        if (cells.size() == 1 && totalCells == 1) {
          singleCell = cells.get(0);
        }
      }
    }
    if (totalCells == 1 && singleCell != null) {
      int len = singleCell.getValueLength();
      if (len > 0 && len % Bytes.SIZEOF_FLOAT == 0) {
        return decodeVector(singleCell.getValueArray(), singleCell.getValueOffset(), len, false,
          SortOrder.ASC);
      }
    }
    return null;
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

    if (scheduledFlushTask != null) {
      scheduledFlushTask.cancel(false);
      scheduledFlushTask = null;
    }
    if (flushScheduler != null) {
      flushScheduler.shutdown();
      try {
        if (!flushScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          flushScheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        flushScheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      flushScheduler = null;
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
    return mutableVectors != null ? mutableVectors.count() : 0;
  }

  public int getLiveNodeCount() {
    return ordinalToKey.size();
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

    public int count() {
      return vectorMap.size();
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

  /**
   * Adapts base on-disk vectors and newly added mutable vectors into a unified
   * {@link RandomAccessVectorValues} sequence for {@link GraphIndexBuilder#buildAndMergeNewNodes}.
   */
  public static class CombinedRandomAccessVectorValues implements RandomAccessVectorValues {
    private final OnDiskGraphIndex onDiskGraphIndex;
    private final RandomAccessVectorValues baseVectors;
    private final int baseCount;
    private final List<VectorFloat<?>> newVectors;
    private final int dimension;

    public CombinedRandomAccessVectorValues(OnDiskGraphIndex onDiskGraphIndex,
      RandomAccessVectorValues baseVectors, int baseCount, List<VectorFloat<?>> newVectors,
      int dimension) {
      this.onDiskGraphIndex = onDiskGraphIndex;
      this.baseVectors = baseVectors;
      this.baseCount = baseCount;
      this.newVectors = newVectors != null ? newVectors : Collections.emptyList();
      this.dimension = dimension;
    }

    public CombinedRandomAccessVectorValues(RandomAccessVectorValues baseVectors, int baseCount,
      List<VectorFloat<?>> newVectors, int dimension) {
      this(null, baseVectors, baseCount, newVectors, dimension);
    }

    @Override
    public int size() {
      return baseCount + newVectors.size();
    }

    @Override
    public int dimension() {
      return dimension;
    }

    @Override
    public VectorFloat<?> getVector(int ordinal) {
      if (ordinal < baseCount) {
        return baseVectors != null ? baseVectors.getVector(ordinal) : null;
      }
      int newIndex = ordinal - baseCount;
      if (newIndex >= 0 && newIndex < newVectors.size()) {
        return newVectors.get(newIndex);
      }
      return null;
    }

    @Override
    public void getVectorInto(int node, VectorFloat<?> destinationVector, int offset) {
      if (node < baseCount && baseVectors != null) {
        baseVectors.getVectorInto(node, destinationVector, offset);
        return;
      }
      VectorFloat<?> v = getVector(node);
      if (v != null) {
        destinationVector.copyFrom(v, 0, offset, dimension);
      }
    }

    @Override
    public boolean isValueShared() {
      return true;
    }

    @Override
    public RandomAccessVectorValues copy() {
      RandomAccessVectorValues base = (onDiskGraphIndex != null)
        ? onDiskGraphIndex.getView()
        : (baseVectors != null ? baseVectors.copy() : null);
      return new CombinedRandomAccessVectorValues(onDiskGraphIndex, base, baseCount, newVectors,
        dimension);
    }
  }

  /**
   * Converts an {@link OnDiskGraphIndex} into the binary format required by
   * {@link io.github.jbellis.jvector.graph.OnHeapGraphIndex#load}.
   */
  public static byte[] convertOnDiskGraphToOnHeapBytes(OnDiskGraphIndex onDiskGraph)
    throws IOException {
    Preconditions.checkNotNull(onDiskGraph, "onDiskGraph cannot be null");
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    dos.writeInt(0x75EC4012); // OnHeapGraphIndex.MAGIC
    dos.writeInt(4); // version
    dos.writeInt(1); // layerCount (layer 0)
    dos.writeInt(onDiskGraph.maxDegree());
    int entryNodeId = -1;
    try (OnDiskGraphIndex.View view = onDiskGraph.getView()) {
      if (view.entryNode() != null) {
        entryNodeId = view.entryNode().node;
      }
    }
    dos.writeInt(entryNodeId);
    dos.writeInt(onDiskGraph.size());
    try (OnDiskGraphIndex.View view = onDiskGraph.getView()) {
      for (int i = 0; i < onDiskGraph.size(); i++) {
        dos.writeInt(i);
        NodesIterator it = view.getNeighborsIterator(0, i);
        dos.writeInt(it.size());
        while (it.hasNext()) {
          dos.writeInt(it.nextInt());
          dos.writeFloat(0.0f);
        }
      }
    }
    dos.flush();
    return baos.toByteArray();
  }

  /**
   * Triggers an asynchronous flush of the mutable buffer if the node count has reached the
   * configured threshold or background compaction is due.
   */
  public void triggerFlushAsync() {
    if (closed || !initialized) {
      return;
    }
    ExecutorService executor = (flushScheduler != null && !flushScheduler.isShutdown())
      ? flushScheduler
      : ForkJoinPool.commonPool();
    executor.execute(() -> {
      try {
        flush();
      } catch (Throwable t) {
        LOG.warn("Async flush failed for table {} region {}", tableName,
          Bytes.toStringBinary(regionName), t);
      }
    });
  }

  /**
   * Flushes and compacts the in-memory mutable graph buffer into an immutable MOB segment.
   * <p>
   * Encapsulates the 5-step lifecycle:
   * <ol>
   * <li><b>Snapshot:</b> Atomically snapshot the active {@link GraphIndexBuilder} and mutable
   * vectors and replace them with empty instances for incoming writes.</li>
   * <li><b>Deletion Pruning:</b> Invoke {@link GraphIndexBuilder#removeDeletedNodes()} on the
   * snapshot.</li>
   * <li><b>Incremental Merge:</b> Invoke {@link GraphIndexBuilder#buildAndMergeNewNodes} to combine
   * new nodes with the existing on-disk graph segment reader without full rebuild.</li>
   * <li><b>Persistence:</b> Serialize the merged graph and write it as a new generation MOB cell,
   * updating {@code SYSTEM.VECTOR_GRAPH_SEGMENT}.</li>
   * <li><b>Cutover:</b> Materialize the new segment into a newly allocated off-heap buffer, update
   * the active segment reference atomically, and release the prior off-heap buffer.</li>
   * </ol>
   * @return true if a flush and compaction was performed, false if no mutations were present to
   *         flush
   */
  public boolean flush() throws IOException {
    checkNotClosed();
    if (!initialized) {
      return false;
    }
    flushLock.lock();
    try {
      if (closed || !initialized) {
        return false;
      }
      isFlushing.set(true);
      return flushInternal();
    } finally {
      isFlushing.set(false);
      flushLock.unlock();
    }
  }

  private boolean flushInternal() throws IOException {
    int curMutableCount = mutableVectors != null ? mutableVectors.count() : 0;
    boolean hasDeletions =
      onDiskGraphIndex != null && ordinalToKey.size() < onDiskGraphIndex.size();
    if (curMutableCount == 0 && !hasDeletions && onDiskGraphIndex != null) {
      return false;
    }
    if (curMutableCount == 0 && onDiskGraphIndex == null) {
      return false;
    }

    // Step 1: Snapshot
    GraphIndexBuilder snapshotBuilder;
    ConcurrentRandomAccessVectorValues snapshotVectors;
    Map<Integer, byte[]> snapshotOrdinalToKey;
    Map<ImmutableBytesPtr, Integer> snapshotKeyToOrdinal;
    synchronized (this) {
      if (mutableVectors.count() == 0 && !hasDeletions && onDiskGraphIndex != null) {
        return false;
      }
      snapshotBuilder = this.mutableBuilder;
      snapshotVectors = this.mutableVectors;
      snapshotOrdinalToKey = new HashMap<>(this.ordinalToKey);
      snapshotKeyToOrdinal = new HashMap<>(this.keyToOrdinal);

      this.mutableVectors = new ConcurrentRandomAccessVectorValues(dimension);
      this.mutableBuilder = createGraphIndexBuilder();
      this.liveMutableOrdinals.clear();

      this.flushingSnapshotBuilder = snapshotBuilder;
      this.flushingSnapshotVectors = snapshotVectors;
      this.flushingSnapshotOrdinalToKey = snapshotOrdinalToKey;
    }

    try {
      // Step 2: Deletion Pruning
      if (snapshotBuilder != null) {
        snapshotBuilder.removeDeletedNodes();
      }

      // Step 3: Incremental Merge
      ImmutableGraphIndex mergedGraph;
      RandomAccessVectorValues mergedVectors;
      Map<Integer, byte[]> preRenumberMapping = new HashMap<>();

      if (onDiskGraphIndex == null) {
        if (snapshotBuilder == null) {
          return false;
        }
        snapshotBuilder.cleanup();
        mergedGraph = snapshotBuilder.getGraph();
        mergedVectors = snapshotVectors;
        for (Map.Entry<Integer, byte[]> entry : snapshotOrdinalToKey.entrySet()) {
          int ord = entry.getKey();
          byte[] pk = entry.getValue();
          if (
            pk != null && Objects.equals(snapshotKeyToOrdinal.get(new ImmutableBytesPtr(pk)), ord)
          ) {
            preRenumberMapping.put(ord, pk);
          }
        }
      } else {
        int startingNodeOffset = onDiskGraphIndex.size();
        try (OnDiskGraphIndex.View onDiskView = onDiskGraphIndex.getView()) {
          List<VectorFloat<?>> newVectorList = new ArrayList<>();
          List<byte[]> newKeyList = new ArrayList<>();

          for (Map.Entry<Integer, byte[]> entry : snapshotOrdinalToKey.entrySet()) {
            int ord = entry.getKey();
            byte[] pk = entry.getValue();
            if (
              ord >= startingNodeOffset && pk != null
                && Objects.equals(snapshotKeyToOrdinal.get(new ImmutableBytesPtr(pk)), ord)
            ) {
              VectorFloat<?> v = snapshotVectors.getVector(ord);
              if (v != null) {
                newVectorList.add(v);
                newKeyList.add(pk);
              }
            }
          }

          // Populate base (existing) nodes in preRenumberMapping
          for (int i = 0; i < startingNodeOffset; i++) {
            byte[] pk = snapshotOrdinalToKey.get(i);
            if (
              pk != null && Objects.equals(snapshotKeyToOrdinal.get(new ImmutableBytesPtr(pk)), i)
            ) {
              preRenumberMapping.put(i, pk);
            }
          }

          // Assign contiguous ordinals to new nodes starting from startingNodeOffset
          for (int i = 0; i < newKeyList.size(); i++) {
            int newOrd = startingNodeOffset + i;
            preRenumberMapping.put(newOrd, newKeyList.get(i));
          }

          CombinedRandomAccessVectorValues combinedRavv = new CombinedRandomAccessVectorValues(
            onDiskGraphIndex, onDiskView, startingNodeOffset, newVectorList, dimension);

          int totalSize = startingNodeOffset + newVectorList.size();
          int[] identityMap = new int[totalSize];
          for (int i = 0; i < totalSize; i++) {
            identityMap[i] = i;
          }
          RemappedRandomAccessVectorValues remapped =
            new RemappedRandomAccessVectorValues(combinedRavv, identityMap);

          BuildScoreProvider bsp =
            BuildScoreProvider.randomAccessScoreProvider(remapped, similarityFunction);

          byte[] onHeapBytes = convertOnDiskGraphToOnHeapBytes(onDiskGraphIndex);
          ByteBufferReader in = new ByteBufferReader(ByteBuffer.wrap(onHeapBytes));

          mergedGraph = GraphIndexBuilder.buildAndMergeNewNodes(in, remapped, bsp,
            startingNodeOffset, efConstruction, neighborOverflow, alpha);
          mergedVectors = combinedRavv;
        }
      }

      // Apply sequential renumbering so on-disk ordinals are 0..N-1 and match mergedMapping
      Map<Integer, Integer> oldToNew = AbstractGraphIndexWriter.sequentialRenumbering(mergedGraph);
      Map<Integer, byte[]> mergedMapping = new HashMap<>();
      for (Map.Entry<Integer, byte[]> entry : preRenumberMapping.entrySet()) {
        int oldOrd = entry.getKey();
        Integer newOrd = oldToNew != null ? oldToNew.get(oldOrd) : oldOrd;
        if (newOrd != null) {
          mergedMapping.put(newOrd, entry.getValue());
        }
      }

      // Step 4: Persistence
      Path tempPath = Files.createTempFile("hnsw-segment-flush-", ".jvec");
      byte[] graphBytes;
      try {
        writeGraphToFile(mergedGraph, mergedVectors, oldToNew, tempPath);
        graphBytes = Files.readAllBytes(tempPath);
      } finally {
        Files.deleteIfExists(tempPath);
      }

      byte[] mappingBytes = serializeOrdinalMapping(mergedMapping);
      byte[] combinedPayload = combineSegmentAndMapping(graphBytes, mappingBytes);

      long nextGenId =
        activeSegmentMetadata != null ? activeSegmentMetadata.getGenerationId() + 1 : 1L;
      byte[] newSegmentRowKey = generateSegmentRowKey(tableName, regionStartKey, nextGenId);

      // Write MOB cell
      Table hTable = null;
      try {
        hTable = getHBaseTable();
        if (hTable != null) {
          writeSegment(hTable, newSegmentRowKey, combinedPayload);
        } else if (env != null && env.getRegion() != null) {
          writeSegment(env.getRegion(), newSegmentRowKey, combinedPayload);
        } else {
          LOG.debug("Neither HBase Table nor Region available to write segment for table {}",
            tableName);
        }
      } finally {
        if (hTable != null && tableSupplier == null) {
          try {
            hTable.close();
          } catch (Exception e) {
            LOG.debug("Error closing table for {}: {}", tableName, e.getMessage());
          }
        }
      }

      // Record metadata in SYSTEM.VECTOR_GRAPH_SEGMENT
      long constructionTime = System.currentTimeMillis();
      String regionEncodedName = regionName != null ? Bytes.toStringBinary(regionName) : "";
      recordSegmentMetadata(tableName, regionStartKey, nextGenId, regionEndKey, regionEncodedName,
        newSegmentRowKey, mergedMapping.size(), constructionTime,
        PhoenixDatabaseMetaData.REBUILD_STATE_COMPLETE);

      // Step 5: Cutover
      ByteBuffer directBuffer;
      SegmentKey newKey = SegmentKey.of(tableName, regionName, newSegmentRowKey);
      if (allocator != null) {
        directBuffer = allocator.allocate(newKey, combinedPayload.length, evictedKey -> {
          try {
            evictSegment();
          } catch (IOException e) {
            LOG.warn("Error evicting segment {}", evictedKey, e);
          }
        });
        directBuffer.put(combinedPayload);
        directBuffer.flip();
      } else {
        directBuffer = copyPayloadToDirectByteBuffer(combinedPayload);
      }

      synchronized (this) {
        loadSegmentAfterFlush(newSegmentRowKey, directBuffer, mergedMapping);
        this.activeSegmentMetadata = new SegmentMetadata(tableName, regionStartKey, nextGenId,
          regionEndKey, regionEncodedName, newSegmentRowKey, mergedMapping.size(), constructionTime,
          PhoenixDatabaseMetaData.REBUILD_STATE_COMPLETE);
        this.flushingSnapshotBuilder = null;
        this.flushingSnapshotVectors = null;
        this.flushingSnapshotOrdinalToKey = null;
      }

      LOG.info("Flushed and compacted HNSW index for table {} region {} (gen={}, nodes={})",
        tableName, Bytes.toStringBinary(regionName), nextGenId, mergedMapping.size());
      return true;
    } finally {
      if (snapshotBuilder != null) {
        try {
          snapshotBuilder.close();
        } catch (Exception e) {
          LOG.warn("Error closing snapshotBuilder during flush for table {}", tableName, e);
        }
      }
      this.flushingSnapshotBuilder = null;
      this.flushingSnapshotVectors = null;
      this.flushingSnapshotOrdinalToKey = null;
    }
  }

  private synchronized void loadSegmentAfterFlush(byte[] newSegmentRowKey, ByteBuffer directBuffer,
    Map<Integer, byte[]> mergedMapping) throws IOException {
    if (
      activeSegmentRowKey != null && !Bytes.equals(activeSegmentRowKey, newSegmentRowKey)
        && allocator != null
    ) {
      allocator.release(getSegmentKey());
    }
    this.activeSegmentRowKey = Arrays.copyOf(newSegmentRowKey, newSegmentRowKey.length);
    if (allocator != null) {
      allocator.register(getSegmentKey(), directBuffer, evictedKey -> {
        try {
          evictSegment();
        } catch (IOException e) {
          LOG.warn("Error evicting segment {}", evictedKey, e);
        }
      });
    }

    closeActiveSegment();

    ByteBuffer graphBuffer = directBuffer;
    if (directBuffer.limit() >= 8) {
      int magic = directBuffer.getInt(directBuffer.limit() - 4);
      if (magic == ORDINAL_MAPPING_MAGIC) {
        Pair<ByteBuffer, Map<Integer, byte[]>> split = splitSegmentAndMapping(directBuffer);
        graphBuffer = split.getFirst();
      }
    }
    this.segmentBuffer = graphBuffer;
    this.readerSupplier = new PhoenixMobReaderSupplier(graphBuffer);
    this.onDiskGraphIndex = OnDiskGraphIndex.load(readerSupplier);

    // Merge segment mapping with any concurrent writes that happened in mutableBuilder during flush
    Map<Integer, byte[]> concurrentMutableEntries = new HashMap<>();
    for (Map.Entry<Integer, byte[]> entry : ordinalToKey.entrySet()) {
      int ord = entry.getKey();
      byte[] pk = entry.getValue();
      if (pk != null && Objects.equals(keyToOrdinal.get(new ImmutableBytesPtr(pk)), ord)) {
        if (mutableVectors != null && mutableVectors.getVector(ord) != null) {
          concurrentMutableEntries.put(ord, pk);
        }
      }
    }

    ordinalToKey.clear();
    keyToOrdinal.clear();
    int maxOrdinal = -1;
    if (mergedMapping != null) {
      for (Map.Entry<Integer, byte[]> entry : mergedMapping.entrySet()) {
        int ordinal = entry.getKey();
        byte[] key = entry.getValue();
        ordinalToKey.put(ordinal, key);
        keyToOrdinal.put(new ImmutableBytesPtr(key), ordinal);
        if (ordinal > maxOrdinal) {
          maxOrdinal = ordinal;
        }
      }
    }

    for (Map.Entry<Integer, byte[]> entry : concurrentMutableEntries.entrySet()) {
      int ord = entry.getKey();
      byte[] key = entry.getValue();
      ordinalToKey.put(ord, key);
      keyToOrdinal.put(new ImmutableBytesPtr(key), ord);
      if (ord > maxOrdinal) {
        maxOrdinal = ord;
      }
    }

    nextOrdinal.accumulateAndGet(maxOrdinal + 1, Math::max);
    this.evicted = false;
  }

  private void writeGraphToFile(ImmutableGraphIndex graph, RandomAccessVectorValues vectors,
    Map<Integer, Integer> oldToNew, Path tempPath) throws IOException {
    if ("SQ8".equalsIgnoreCase(quantizationType)) {
      NVQuantization nvq = NVQuantization.compute(vectors, 1);
      try (OnDiskGraphIndexWriter writer = new OnDiskGraphIndexWriter.Builder(graph, tempPath)
        .withMap(
          oldToNew != null ? oldToNew : AbstractGraphIndexWriter.sequentialRenumbering(graph))
        .with(new InlineVectors(vectors.dimension())).with(new NVQ(nvq)).build()) {
        Map<FeatureId, IntFunction<Feature.State>> suppliers = new EnumMap<>(FeatureId.class);
        suppliers.put(FeatureId.INLINE_VECTORS,
          nodeId -> new InlineVectors.State(vectors.getVector(nodeId)));
        suppliers.put(FeatureId.NVQ_VECTORS,
          nodeId -> new NVQ.State(nvq.encode(vectors.getVector(nodeId))));
        writer.write(suppliers);
      }
    } else if ("PQ".equalsIgnoreCase(quantizationType)) {
      int pqSegments = 16;
      if (dimension % pqSegments != 0) {
        pqSegments = (dimension % 8 == 0) ? 8 : (dimension % 4 == 0 ? 4 : 1);
      }
      ProductQuantization pq = HnswPqCodebookTrainer.trainCodebook(vectors, pqSegments);
      NVQuantization nvq = NVQuantization.compute(vectors, 1);
      int maxDegree = graph.maxDegree();
      try (OnDiskGraphIndexWriter writer = new OnDiskGraphIndexWriter.Builder(graph, tempPath)
        .withMap(
          oldToNew != null ? oldToNew : AbstractGraphIndexWriter.sequentialRenumbering(graph))
        .with(new InlineVectors(vectors.dimension())).with(new FusedPQ(maxDegree, pq))
        .with(new NVQ(nvq)).build()) {
        try (ImmutableGraphIndex.View graphView = graph.getView()) {
          PQVectors pqv = (PQVectors) pq.encodeAll(vectors);
          Map<FeatureId, IntFunction<Feature.State>> suppliers = new EnumMap<>(FeatureId.class);
          suppliers.put(FeatureId.INLINE_VECTORS,
            nodeId -> new InlineVectors.State(vectors.getVector(nodeId)));
          suppliers.put(FeatureId.FUSED_PQ, nodeId -> new FusedPQ.State(graphView, pqv, nodeId));
          suppliers.put(FeatureId.NVQ_VECTORS,
            nodeId -> new NVQ.State(nvq.encode(vectors.getVector(nodeId))));
          writer.write(suppliers);
        }
      }
    } else {
      if (oldToNew != null) {
        OnDiskGraphIndex.write(graph, vectors, oldToNew, tempPath);
      } else {
        OnDiskGraphIndex.write(graph, vectors, tempPath);
      }
    }
  }

  /**
   * Generates the deterministic row key for storing the segment MOB cell. Format:
   * {@code [indexNameBytes][0x00][regionStartKey][0x00][generationIdBytes]}.
   */
  public static byte[] generateSegmentRowKey(String indexTableName, byte[] regionStartKey,
    long generationId) {
    return HnswGraphBuildMapper.generateSegmentRowKey(indexTableName, regionStartKey, generationId);
  }

  /**
   * Generates the deterministic segment row key for this manager's table and region start key.
   */
  public byte[] generateSegmentRowKey(long generationId) {
    return generateSegmentRowKey(tableName, regionStartKey, generationId);
  }

  /** Records segment metadata into {@code SYSTEM.VECTOR_GRAPH_SEGMENT}. */
  public void recordSegmentMetadata(String indexName, byte[] regionStartKey, long generationId,
    byte[] regionEndKey, String regionEncodedName, byte[] segmentRowKey, long nodeCount,
    long constructionTime, String rebuildState) {
    Connection conn = null;
    boolean closeConn = false;
    try {
      conn = getPhoenixConnection();
      if (conn == null) {
        return;
      }
      closeConn = true;
      recordSegmentMetadata(conn, indexName, regionStartKey, generationId, regionEndKey,
        regionEncodedName, segmentRowKey, nodeCount, constructionTime, rebuildState);
    } catch (Exception e) {
      LOG.warn(
        "Failed to record segment metadata in SYSTEM.VECTOR_GRAPH_SEGMENT for table {} gen {}: {}",
        indexName, generationId, e.getMessage());
    } finally {
      if (closeConn && conn != null) {
        try {
          conn.close();
        } catch (Exception e) {
          LOG.debug("Error closing connection for table {}: {}", indexName, e.getMessage());
        }
      }
    }
  }

  /**
   * Records segment metadata into {@code SYSTEM.VECTOR_GRAPH_SEGMENT} using a provided connection.
   */
  public static void recordSegmentMetadata(Connection conn, String indexName, byte[] regionStartKey,
    long generationId, byte[] regionEndKey, String regionEncodedName, byte[] segmentRowKey,
    long nodeCount, long constructionTime, String rebuildState) throws SQLException {
    Preconditions.checkNotNull(conn, "conn cannot be null");
    Preconditions.checkNotNull(indexName, "indexName cannot be null");

    String upsertSql = "UPSERT INTO " + PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_NAME
      + " (" + PhoenixDatabaseMetaData.INDEX_NAME + ", " + PhoenixDatabaseMetaData.REGION_START_KEY
      + ", " + PhoenixDatabaseMetaData.GENERATION_ID + ", " + PhoenixDatabaseMetaData.REGION_END_KEY
      + ", " + PhoenixDatabaseMetaData.REGION_ENCODED_NAME + ", "
      + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + ", " + PhoenixDatabaseMetaData.NODE_COUNT + ", "
      + PhoenixDatabaseMetaData.CONSTRUCTION_TIME + ", " + PhoenixDatabaseMetaData.REBUILD_STATE
      + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
      ps.setString(1, indexName);
      if (regionStartKey != null && regionStartKey.length > 0) {
        ps.setBytes(2, regionStartKey);
      } else {
        ps.setNull(2, java.sql.Types.VARBINARY);
      }
      ps.setLong(3, generationId);
      ps.setBytes(4, regionEndKey != null ? regionEndKey : HConstants.EMPTY_BYTE_ARRAY);
      ps.setString(5, regionEncodedName != null ? regionEncodedName : "");
      ps.setBytes(6, segmentRowKey);
      ps.setLong(7, nodeCount);
      ps.setLong(8, constructionTime);
      ps.setString(9,
        rebuildState != null ? rebuildState : PhoenixDatabaseMetaData.REBUILD_STATE_COMPLETE);
      ps.executeUpdate();
    }
    if (!conn.getAutoCommit()) {
      conn.commit();
    }
  }

  public boolean isFlushing() {
    return isFlushing.get();
  }

  public GraphIndexBuilder getFlushingSnapshotBuilder() {
    return flushingSnapshotBuilder;
  }

  public String getQuantizationType() {
    return quantizationType;
  }

  public int getFlushThreshold() {
    return flushThreshold;
  }

  public long getFlushIntervalMs() {
    return flushIntervalMs;
  }
}
