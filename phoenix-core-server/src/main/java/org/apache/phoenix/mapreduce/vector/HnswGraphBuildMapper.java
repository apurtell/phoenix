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
package org.apache.phoenix.mapreduce.vector;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndexWriter;
import io.github.jbellis.jvector.graph.disk.feature.Feature;
import io.github.jbellis.jvector.graph.disk.feature.FeatureId;
import io.github.jbellis.jvector.graph.disk.feature.FusedPQ;
import io.github.jbellis.jvector.graph.disk.feature.NVQ;
import io.github.jbellis.jvector.quantization.NVQuantization;
import io.github.jbellis.jvector.quantization.PQVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.phoenix.hbase.index.vector.HnswIndexManager;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;
import org.apache.phoenix.mapreduce.PhoenixJobCounters;
import org.apache.phoenix.mapreduce.index.DirectHTableWriter;
import org.apache.phoenix.mapreduce.index.PhoenixIndexDBWritable;
import org.apache.phoenix.mapreduce.util.ConnectionUtil;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.SaltingUtil;
import org.apache.phoenix.schema.types.PVectorDouble;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.ColumnInfo;
import org.apache.phoenix.util.EncodedColumnsUtil;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.IndexUtil.IndexStatusUpdater;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.SchemaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Mapper for bulk initial construction of HNSW vector indexes via {@code IndexTool}.
 * <p>
 * Unlike IVF indexes, which cluster vectors into global centroids across the full dataset, HNSW
 * graphs are constructed per region:
 * <ol>
 * <li>Each mapper processes exactly one base table region (enforced via
 * {@link HnswGraphBuildInputFormat}).</li>
 * <li>Vectors are accumulated in-memory into a JVector {@link GraphIndexBuilder} during
 * {@link #map}.</li>
 * <li>During {@link #cleanup}, the complete graph is built and serialized into JVector's on-disk
 * format ({@link OnDiskGraphIndex}) with an embedded ordinal-to-primary-key mapping trailer.</li>
 * <li>The serialized graph segment is written as a MOB cell to the index table.</li>
 * <li>Generation-0 segment metadata is recorded in {@code SYSTEM.VECTOR_GRAPH_SEGMENT}.</li>
 * <li>A dummy key-value pair is emitted to the reducer
 * ({@link org.apache.phoenix.mapreduce.index.PhoenixIndexImportDirectReducer}) to transition the
 * index state from {@code BUILDING} to {@code ACTIVE}.</li>
 * </ol>
 * </p>
 */
public class HnswGraphBuildMapper
  extends Mapper<NullWritable, PhoenixIndexDBWritable, ImmutableBytesWritable, IntWritable> {

  private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphBuildMapper.class);

  public static final long GENERATION_ZERO = 0L;

  private GraphIndexBuilder graphBuilder;
  private HnswIndexManager.ConcurrentRandomAccessVectorValues vectorValues;
  private final Map<Integer, byte[]> ordinalToKey = new LinkedHashMap<>();
  private final AtomicInteger nextOrdinal = new AtomicInteger(0);
  private int m = HnswIndexManager.DEFAULT_M;
  private int efConstruction = HnswIndexManager.DEFAULT_EF_CONSTRUCTION;
  private float alpha = HnswIndexManager.DEFAULT_ALPHA;
  private int dimension = 128;
  private String distanceMetric = "COSINE";
  private String quantizationType;
  private String indexTableName;
  private String dataTableName;
  private VectorSimilarityFunction similarityFunction;
  private byte[] regionStartKey;
  private byte[] regionEndKey;
  private String regionEncodedName;
  private ProductQuantization pqCodebook;
  private DirectHTableWriter writer;
  private Connection connection;
  private IndexStatusUpdater indexStatusUpdater;
  private int vectorColumnIndex = -1;
  private int totalRowCount = 0;
  private PTable pDataTable;
  private PTable pIndexTable;
  private List<PColumn> pkColumns;
  private int[] pkColumnIndexesInValues;
  private boolean dummyEmitted = false;

  @Override
  protected void setup(Context context) throws IOException, InterruptedException {
    super.setup(context);
    Configuration conf = context.getConfiguration();

    // 1. Read HNSW parameters from Configuration
    m = PhoenixConfigurationUtil.getHnswM(conf);
    efConstruction = PhoenixConfigurationUtil.getHnswEfConstruction(conf);
    alpha = (float) PhoenixConfigurationUtil.getHnswAlpha(conf);
    dimension = PhoenixConfigurationUtil.getVectorDimension(conf);
    distanceMetric = PhoenixConfigurationUtil.getVectorDistanceMetric(conf);
    if (distanceMetric == null) {
      distanceMetric = "COSINE";
    }
    quantizationType = PhoenixConfigurationUtil.getHnswQuantizationType(conf);
    indexTableName = PhoenixConfigurationUtil.getIndexToolIndexTableName(conf);
    dataTableName = PhoenixConfigurationUtil.getIndexToolDataTableName(conf);
    if (dataTableName == null) {
      dataTableName = PhoenixConfigurationUtil.getInputTableName(conf);
    }
    similarityFunction = HnswIndexManager.resolveSimilarityFunction(distanceMetric);

    // 2. Extract region context from scan attributes and assert strict 1:1 invariant
    InputSplit split = context.getInputSplit();
    if (split instanceof PhoenixInputSplit) {
      PhoenixInputSplit inputSplit = (PhoenixInputSplit) split;
      Preconditions.checkState(!inputSplit.isCoalesced(),
        "HNSW build requires exactly one region per input split; got coalesced split");
      List<Scan> scans = inputSplit.getScans();
      if (scans != null && !scans.isEmpty()) {
        Scan firstScan = scans.get(0);
        regionStartKey =
          firstScan.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_START_KEY_ATTR);
        regionEndKey = firstScan.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_END_KEY_ATTR);
        byte[] encBytes =
          firstScan.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_ENCODED_NAME_ATTR);
        regionEncodedName = (encBytes != null) ? Bytes.toString(encBytes) : null;
      }
    }

    // 3. Initialize JVector graph builder
    vectorValues = new HnswIndexManager.ConcurrentRandomAccessVectorValues(dimension);
    graphBuilder = new GraphIndexBuilder(vectorValues, similarityFunction, m, efConstruction, alpha,
      HnswIndexManager.DEFAULT_NEIGHBOR_OVERFLOW, true);

    // 4. Load PQ codebook if applicable
    if ("PQ".equalsIgnoreCase(quantizationType)) {
      loadPqCodebook(conf, context);
    }

    // 5. Initialize DirectHTableWriter, Connection, and IndexStatusUpdater unless test mode
    boolean isTestMode = conf.getBoolean("phoenix.hnsw.mapper.test.mode", false);
    if (!isTestMode) {
      try {
        final Properties overrideProps = new Properties();
        String scn = conf.get(PhoenixConfigurationUtil.CURRENT_SCN_VALUE);
        String txScnValue = conf.get(PhoenixConfigurationUtil.TX_SCN_VALUE);
        if (txScnValue == null && scn != null) {
          overrideProps.put(PhoenixRuntime.BUILD_INDEX_AT_ATTRIB, scn);
        }
        connection = ConnectionUtil.getOutputConnection(conf, overrideProps);
        connection.setAutoCommit(false);

        String outputTable = conf.get(TableOutputFormat.OUTPUT_TABLE);
        if (outputTable == null) {
          outputTable = PhoenixConfigurationUtil.getPhysicalTableName(conf);
          if (outputTable != null) {
            conf.set(TableOutputFormat.OUTPUT_TABLE, outputTable);
          }
        }
        if (outputTable != null) {
          writer = new DirectHTableWriter(conf);
        }

        PhoenixConnection pConn = connection.unwrap(PhoenixConnection.class);
        if (indexTableName != null) {
          try {
            pIndexTable = pConn.getTable(indexTableName);
            if (pIndexTable != null) {
              indexStatusUpdater =
                new IndexStatusUpdater(SchemaUtil.getEmptyColumnFamily(pIndexTable),
                  EncodedColumnsUtil.getEmptyKeyValueInfo(pIndexTable).getFirst());
            }
          } catch (Exception e) {
            LOGGER.warn("Could not load PTable for index table: " + indexTableName, e);
          }
        }

        if (dataTableName != null) {
          try {
            pDataTable = pConn.getTable(dataTableName);
          } catch (Exception e) {
            LOGGER.warn("Could not load PTable for data table: " + dataTableName, e);
          }
        }
      } catch (Exception e) {
        LOGGER.error("Failed to initialize database resources in HnswGraphBuildMapper.setup()", e);
        closeResources();
        throw new RuntimeException(e);
      }
    }

    // 6. Resolve vector column index and primary key mapping
    List<ColumnInfo> upsertColMetadata = null;
    try {
      upsertColMetadata = PhoenixConfigurationUtil.getUpsertColumnMetadataList(conf);
    } catch (SQLException e) {
      LOGGER.warn("Could not retrieve upsert column metadata list: " + e.getMessage());
    }

    initPrimaryKeyMetadata(conf, upsertColMetadata);
    vectorColumnIndex = PhoenixConfigurationUtil.getVectorIndexInSelected(conf);
    if (vectorColumnIndex < 0 && upsertColMetadata != null) {
      for (int i = 0; i < upsertColMetadata.size(); i++) {
        ColumnInfo colInfo = upsertColMetadata.get(i);
        if (
          colInfo.getPDataType() == PVectorFloat.INSTANCE
            || colInfo.getPDataType() == PVectorDouble.INSTANCE
            || (colInfo.getPDataType() != null && colInfo.getPDataType().isVectorType())
        ) {
          vectorColumnIndex = i;
          break;
        }
      }
    }

    totalRowCount = 0;
    dummyEmitted = false;
  }

  @Override
  protected void map(NullWritable key, PhoenixIndexDBWritable record, Context context)
    throws IOException, InterruptedException {
    List<Object> values = record.getValues();
    if (values == null || values.isEmpty()) {
      if (context != null) {
        context.getCounter(PhoenixJobCounters.INPUT_RECORDS).increment(1);
      }
      return;
    }

    // Auto-detect vector column if not yet resolved
    if (vectorColumnIndex < 0) {
      for (int i = 0; i < values.size(); i++) {
        Object val = values.get(i);
        if (
          val instanceof float[] || val instanceof Float[] || val instanceof double[]
            || val instanceof Double[]
        ) {
          vectorColumnIndex = i;
          break;
        }
      }
    }

    // 1. Validate and extract vector
    if (vectorColumnIndex < 0 || vectorColumnIndex >= values.size()) {
      if (context != null) {
        context.getCounter(PhoenixJobCounters.INPUT_RECORDS).increment(1);
      }
      return;
    }

    Object vectorObj = values.get(vectorColumnIndex);
    if (vectorObj == null) {
      if (context != null) {
        context.getCounter(PhoenixJobCounters.INPUT_RECORDS).increment(1);
      }
      return;
    }

    float[] floats;
    try {
      floats = extractFloatVector(vectorObj);
    } catch (SQLException e) {
      LOGGER.warn("Failed to extract float vector from record", e);
      if (context != null) {
        context.getCounter(PhoenixJobCounters.INPUT_RECORDS).increment(1);
      }
      return;
    }

    if (floats == null || floats.length != dimension) {
      LOGGER.warn("Skipping record with vector dimension mismatch (got {}, expected {})",
        floats == null ? 0 : floats.length, dimension);
      if (context != null) {
        context.getCounter(PhoenixJobCounters.INPUT_RECORDS).increment(1);
      }
      return;
    }

    // 2. Extract base table primary key from record values
    byte[] primaryKey;
    try {
      primaryKey = extractPrimaryKey(values);
    } catch (SQLException e) {
      LOGGER.error("Failed to extract primary key for vector", e);
      throw new RuntimeException(e);
    }

    // 3. Assign sequential ordinal and insert into graph builder
    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    VectorFloat<?> vector = vts.createFloatVector(floats);

    int ordinal = nextOrdinal.getAndIncrement();
    vectorValues.putVector(ordinal, vector);
    ordinalToKey.put(ordinal, primaryKey);
    graphBuilder.addGraphNode(ordinal, vector);

    totalRowCount++;
    if (totalRowCount % 10000 == 0) {
      LOGGER.info("Region {}: processed {} vectors into HNSW graph builder", regionEncodedName,
        totalRowCount);
    }

    if (context != null) {
      context.getCounter(PhoenixJobCounters.INPUT_RECORDS).increment(1);
      context.progress(); // Heartbeat to prevent YARN task timeout
    }
  }

  @Override
  protected void cleanup(Context context) throws IOException, InterruptedException {
    try {
      if (totalRowCount == 0) {
        LOGGER.info("No vectors in region {} - skipping segment creation", regionEncodedName);
        emitDummyOutput(context);
        return;
      }

      long constructionTime = System.currentTimeMillis();

      // 1. Build and finalize the in-memory graph
      LOGGER.info("Finalizing HNSW graph for region {} with {} vectors", regionEncodedName,
        totalRowCount);
      graphBuilder.cleanup();

      // 2. Serialize graph segment with embedded ordinal mapping trailer
      byte[] segmentBytes = serializeGraphSegment();

      // 3. Generate segment row key
      byte[] segmentRowKey = generateSegmentRowKey();

      // 4. Write graph segment as MOB cell to the index table
      writeSegmentMobCell(segmentRowKey, segmentBytes);

      // 5. Record generation-0 metadata in SYSTEM.VECTOR_GRAPH_SEGMENT
      recordSegmentMetadata(segmentRowKey, constructionTime);

      LOGGER.info("Wrote HNSW segment for region {} ({} vectors, {} bytes)", regionEncodedName,
        totalRowCount, segmentBytes.length);

      if (context != null) {
        context.getCounter(PhoenixJobCounters.OUTPUT_RECORDS).increment(1);
      }
    } catch (Exception e) {
      LOGGER.error("Failed to build HNSW graph for region {}", regionEncodedName, e);
      if (context != null) {
        context.getCounter(PhoenixJobCounters.FAILED_RECORDS).increment(totalRowCount);
      }
      throw new RuntimeException(e);
    } finally {
      // 6. Emit dummy output for reducer and release resources
      emitDummyOutput(context);
      closeResources();
      super.cleanup(context);
    }
  }

  /**
   * Serializes the built graph to JVector on-disk format and appends the ordinal-to-PK mapping.
   */
  protected byte[] serializeGraphSegment() throws IOException {
    java.nio.file.Path tempPath = Files.createTempFile("hnsw-segment-", ".jvec");
    try {
      ImmutableGraphIndex onHeapGraph = graphBuilder.getGraph();
      if ("SQ8".equalsIgnoreCase(quantizationType)) {
        NVQuantization nvq = NVQuantization.compute(vectorValues, 1);
        try (
          OnDiskGraphIndexWriter writer = new OnDiskGraphIndexWriter.Builder(onHeapGraph, tempPath)
            .withMap(OnDiskGraphIndexWriter.sequentialRenumbering(onHeapGraph)).with(new NVQ(nvq))
            .build()) {
          Map<FeatureId, IntFunction<Feature.State>> suppliers =
            Feature.singleStateFactory(FeatureId.NVQ_VECTORS,
              nodeId -> new NVQ.State(nvq.encode(vectorValues.getVector(nodeId))));
          writer.write(suppliers);
        }
      } else if ("PQ".equalsIgnoreCase(quantizationType)) {
        ProductQuantization pq = this.pqCodebook;
        if (pq == null) {
          int pqSegments = 16;
          if (dimension % pqSegments != 0) {
            pqSegments = (dimension % 8 == 0) ? 8 : (dimension % 4 == 0 ? 4 : 1);
          }
          pq = HnswPqCodebookTrainer.trainCodebook(vectorValues, pqSegments);
        }
        NVQuantization nvq = NVQuantization.compute(vectorValues, 1);
        int maxDegree = onHeapGraph.maxDegree();
        try (
          OnDiskGraphIndexWriter writer = new OnDiskGraphIndexWriter.Builder(onHeapGraph, tempPath)
            .withMap(OnDiskGraphIndexWriter.sequentialRenumbering(onHeapGraph))
            .with(new FusedPQ(maxDegree, pq)).with(new NVQ(nvq)).build()) {
          try (ImmutableGraphIndex.View graphView = onHeapGraph.getView()) {
            PQVectors pqv = (PQVectors) pq.encodeAll(vectorValues);
            Map<FeatureId, IntFunction<Feature.State>> suppliers = new EnumMap<>(FeatureId.class);
            suppliers.put(FeatureId.FUSED_PQ, nodeId -> new FusedPQ.State(graphView, pqv, nodeId));
            suppliers.put(FeatureId.NVQ_VECTORS,
              nodeId -> new NVQ.State(nvq.encode(vectorValues.getVector(nodeId))));
            writer.write(suppliers);
          }
        }
      } else {
        OnDiskGraphIndex.write(onHeapGraph, vectorValues, tempPath);
      }
      byte[] graphBytes = Files.readAllBytes(tempPath);

      // Serialize ordinal-to-PK mapping
      byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(ordinalToKey);

      // Combine: [graph bytes][mapping bytes][mapping length (4B)][magic (4B)]
      return HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);
    } finally {
      Files.deleteIfExists(tempPath);
    }
  }

  /**
   * Generates the deterministic row key for storing the segment MOB cell. Format:
   * {@code [indexNameBytes][0x00][regionStartKey][0x00][generationIdBytes]}.
   */
  protected byte[] generateSegmentRowKey() {
    return generateSegmentRowKey(indexTableName, regionStartKey, GENERATION_ZERO);
  }

  /** Static helper for generating deterministic segment row keys. */
  public static byte[] generateSegmentRowKey(String indexTableName, byte[] regionStartKey,
    long generationId) {
    Preconditions.checkNotNull(indexTableName, "indexTableName must not be null");
    byte[] indexNameBytes = Bytes.toBytes(indexTableName);
    byte[] genBytes = Bytes.toBytes(generationId);
    byte[] separator = new byte[] { 0x00 };

    int totalLen = indexNameBytes.length + separator.length
      + (regionStartKey != null ? regionStartKey.length : 0) + separator.length + genBytes.length;
    ByteBuffer buf = ByteBuffer.allocate(totalLen);
    buf.put(indexNameBytes);
    buf.put(separator);
    if (regionStartKey != null) {
      buf.put(regionStartKey);
    }
    buf.put(separator);
    buf.put(genBytes);
    return buf.array();
  }

  /** Writes the serialized graph segment as a MOB cell to the physical index table. */
  protected void writeSegmentMobCell(byte[] segmentRowKey, byte[] segmentBytes) throws IOException {
    if (writer == null) {
      LOGGER.warn("DirectHTableWriter is null; skipping MOB cell write");
      return;
    }
    Put put =
      HnswIndexManager.createSegmentPut(segmentRowKey, HnswIndexManager.DEFAULT_SEGMENT_FAMILY,
        HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER, segmentBytes);

    if (indexStatusUpdater != null) {
      indexStatusUpdater.setVerified(put.cellScanner());
    }

    try {
      writer.write(Collections.singletonList(put));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while writing MOB cell to HBase", e);
    }
  }

  /** Records generation-0 segment metadata into {@code SYSTEM.VECTOR_GRAPH_SEGMENT}. */
  protected void recordSegmentMetadata(byte[] segmentRowKey, long constructionTime)
    throws SQLException {
    if (connection == null) {
      LOGGER.warn("Phoenix connection is null; skipping segment metadata recording");
      return;
    }
    String upsertSql = "UPSERT INTO " + PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_NAME
      + " (" + PhoenixDatabaseMetaData.INDEX_NAME + ", " + PhoenixDatabaseMetaData.REGION_START_KEY
      + ", " + PhoenixDatabaseMetaData.GENERATION_ID + ", " + PhoenixDatabaseMetaData.REGION_END_KEY
      + ", " + PhoenixDatabaseMetaData.REGION_ENCODED_NAME + ", "
      + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + ", " + PhoenixDatabaseMetaData.NODE_COUNT + ", "
      + PhoenixDatabaseMetaData.CONSTRUCTION_TIME + ", " + PhoenixDatabaseMetaData.REBUILD_STATE
      + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    try (PreparedStatement ps = connection.prepareStatement(upsertSql)) {
      ps.setString(1, indexTableName);
      if (regionStartKey != null && regionStartKey.length > 0) {
        ps.setBytes(2, regionStartKey);
      } else {
        ps.setNull(2, java.sql.Types.VARBINARY);
      }
      ps.setLong(3, GENERATION_ZERO);
      ps.setBytes(4, regionEndKey != null ? regionEndKey : HConstants.EMPTY_BYTE_ARRAY);
      ps.setString(5, regionEncodedName);
      ps.setBytes(6, segmentRowKey);
      ps.setLong(7, totalRowCount);
      ps.setLong(8, constructionTime);
      ps.setString(9, PhoenixDatabaseMetaData.REBUILD_STATE_COMPLETE);
      ps.executeUpdate();
    }
    connection.commit();
  }

  /**
   * Emits a single dummy key-value pair to the reducer so that the reducer runs once and
   * transitions the index to ACTIVE state.
   */
  protected void emitDummyOutput(Context context) throws IOException, InterruptedException {
    if (context != null && !dummyEmitted) {
      context.write(
        new ImmutableBytesWritable(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)),
        new IntWritable(0));
      dummyEmitted = true;
    }
  }

  /** Extracts primary key bytes from the given record values list. */
  protected byte[] extractPrimaryKey(List<Object> values) throws SQLException {
    if (pDataTable != null && pkColumns != null && !pkColumns.isEmpty()) {
      byte[][] pkValues = new byte[pkColumns.size()][];
      PhoenixConnection pConn =
        (connection != null) ? connection.unwrap(PhoenixConnection.class) : null;

      for (int i = 0; i < pkColumns.size(); i++) {
        PColumn col = pkColumns.get(i);
        int valIdx = (pkColumnIndexesInValues != null && i < pkColumnIndexesInValues.length)
          ? pkColumnIndexesInValues[i]
          : -1;

        if (valIdx == -2) {
          // Salt column: calculated automatically by pDataTable.newKey()
          pkValues[i] = null;
        } else if (valIdx == -3) {
          // Multi-tenant tenant ID
          pkValues[i] =
            (pConn != null && pConn.getTenantId() != null) ? pConn.getTenantId().getBytes() : null;
        } else if (valIdx >= 0 && valIdx < values.size()) {
          Object val = values.get(valIdx);
          if (val == null) {
            pkValues[i] = ByteUtil.EMPTY_BYTE_ARRAY;
          } else if (val instanceof byte[]) {
            pkValues[i] = (byte[]) val;
          } else {
            pkValues[i] = col.getDataType().toBytes(val, col.getSortOrder());
          }
        } else {
          pkValues[i] = ByteUtil.EMPTY_BYTE_ARRAY;
        }
      }

      ImmutableBytesWritable ptr = new ImmutableBytesWritable();
      pDataTable.newKey(ptr, pkValues);
      return Arrays.copyOfRange(ptr.get(), ptr.getOffset(), ptr.getOffset() + ptr.getLength());
    }

    // Fallback: extract primary key from first non-vector column or synthesize from ordinal
    if (values != null && !values.isEmpty()) {
      int pkIdx = (vectorColumnIndex == 0) ? 1 : 0;
      if (pkIdx < values.size()) {
        Object pkObj = values.get(pkIdx);
        if (pkObj instanceof byte[]) {
          return (byte[]) pkObj;
        } else if (pkObj != null) {
          return Bytes.toBytes(pkObj.toString());
        }
      }
    }
    return Bytes.toBytes("pk_" + totalRowCount);
  }

  /** Initializes primary key column mappings between base table and record projection list. */
  private void initPrimaryKeyMetadata(Configuration conf, List<ColumnInfo> upsertColMetadata) {
    if (pDataTable != null) {
      pkColumns = pDataTable.getPKColumns();
      if (pkColumns != null && !pkColumns.isEmpty()) {
        pkColumnIndexesInValues = new int[pkColumns.size()];

        PhoenixConnection pConn = null;
        try {
          if (connection != null) {
            pConn = connection.unwrap(PhoenixConnection.class);
          }
        } catch (SQLException ignored) {
        }

        for (int i = 0; i < pkColumns.size(); i++) {
          PColumn pkCol = pkColumns.get(i);
          if (
            (pDataTable.getBucketNum() != null && i == 0)
              || SaltingUtil.SALTING_COLUMN_NAME.equals(pkCol.getName().getString())
          ) {
            pkColumnIndexesInValues[i] = -2; // Salt bucket
            continue;
          }
          if (
            pDataTable.isMultiTenant() && pConn != null && pConn.getTenantId() != null
              && ((pDataTable.getBucketNum() != null && i == 1)
                || (pDataTable.getBucketNum() == null && i == 0))
          ) {
            pkColumnIndexesInValues[i] = -3; // Tenant ID
            continue;
          }

          int matchedIdx = -1;
          if (upsertColMetadata != null) {
            for (int j = 0; j < upsertColMetadata.size(); j++) {
              String colName = upsertColMetadata.get(j).getColumnName();
              String dataColName = IndexUtil.getDataColumnName(colName);
              if (dataColName.equalsIgnoreCase(pkCol.getName().getString())) {
                matchedIdx = j;
                break;
              }
            }
          }
          if (matchedIdx == -1) {
            // Positional fallback: account for salt bucket prefix if present
            matchedIdx = (pDataTable.getBucketNum() != null ? i - 1 : i);
          }
          pkColumnIndexesInValues[i] = matchedIdx;
        }
      }
    }
  }

  /** Loads a global Product Quantization codebook from DistributedCache or HDFS. */
  private void loadPqCodebook(Configuration conf, Context context) throws IOException {
    String codebookPathStr = PhoenixConfigurationUtil.getHnswPqCodebookPath(conf);
    if (codebookPathStr == null) {
      LOGGER.warn("PQ quantization requested but phoenix.vector.hnsw.pq.codebook.path is not set");
      return;
    }
    Path codebookPath = new Path(codebookPathStr);
    Path targetPath = null;
    try {
      URI[] cacheFiles = context.getCacheFiles();
      if (cacheFiles != null) {
        for (URI uri : cacheFiles) {
          if (uri.getPath() != null && uri.getPath().endsWith(codebookPath.getName())) {
            targetPath = new Path(uri);
            break;
          }
        }
      }
    } catch (Exception e) {
      LOGGER.warn("Error reading cache files from context: " + e.getMessage());
    }
    if (targetPath == null) {
      targetPath = codebookPath;
    }
    try {
      this.pqCodebook = HnswPqCodebookTrainer.loadCodebook(targetPath, conf);
      LOGGER.info("Loaded PQ codebook from {}", targetPath);
    } catch (Exception e) {
      if (!targetPath.equals(codebookPath)) {
        LOGGER.warn("Failed to load PQ codebook from cache file {}, falling back to {}", targetPath,
          codebookPath, e);
        try {
          this.pqCodebook = HnswPqCodebookTrainer.loadCodebook(codebookPath, conf);
          LOGGER.info("Loaded PQ codebook from fallback path {}", codebookPath);
        } catch (Exception ex) {
          LOGGER.error("Failed to load PQ codebook from fallback path: " + codebookPath, ex);
          throw new RuntimeException(ex);
        }
      } else {
        LOGGER.error("Failed to load PQ codebook from: " + targetPath, e);
        throw new RuntimeException(e);
      }
    }
  }

  /** Extracts float array from raw object representation. */
  public static float[] extractFloatVector(Object obj) throws SQLException {
    if (obj == null) {
      return null;
    }
    if (obj instanceof float[]) {
      return (float[]) obj;
    }
    if (obj instanceof double[]) {
      double[] d = (double[]) obj;
      float[] f = new float[d.length];
      for (int i = 0; i < d.length; i++) {
        f[i] = (float) d[i];
      }
      return f;
    }
    if (obj instanceof Float[]) {
      Float[] fArr = (Float[]) obj;
      float[] f = new float[fArr.length];
      for (int i = 0; i < fArr.length; i++) {
        f[i] = fArr[i] != null ? fArr[i] : 0f;
      }
      return f;
    }
    if (obj instanceof Double[]) {
      Double[] dArr = (Double[]) obj;
      float[] f = new float[dArr.length];
      for (int i = 0; i < dArr.length; i++) {
        f[i] = dArr[i] != null ? dArr[i].floatValue() : 0f;
      }
      return f;
    }
    if (obj instanceof Array) {
      Object inner = ((Array) obj).getArray();
      return extractFloatVector(inner);
    }
    if (obj instanceof byte[]) {
      byte[] b = (byte[]) obj;
      return PVectorFloat.readElements(b, 0, b.length);
    }
    return null;
  }

  private void closeResources() {
    if (this.connection != null) {
      try {
        this.connection.close();
      } catch (SQLException e) {
        LOGGER.error("Error while closing connection in HnswGraphBuildMapper", e);
      }
    }
    if (this.writer != null) {
      try {
        this.writer.close();
      } catch (Exception e) {
        LOGGER.error("Error while closing DirectHTableWriter in HnswGraphBuildMapper", e);
      }
    }
    if (this.graphBuilder != null) {
      try {
        this.graphBuilder.close();
      } catch (Exception e) {
        LOGGER.error("Error while closing graphBuilder in HnswGraphBuildMapper", e);
      }
    }
  }

  @VisibleForTesting
  public void initForTesting(int dimension, int m, int efConstruction, float alpha,
    String distanceMetric, String indexTableName, byte[] regionStartKey, byte[] regionEndKey,
    String regionEncodedName) {
    initForTesting(dimension, m, efConstruction, alpha, distanceMetric, indexTableName,
      regionStartKey, regionEndKey, regionEncodedName, null, null);
  }

  @VisibleForTesting
  public void initForTesting(int dimension, int m, int efConstruction, float alpha,
    String distanceMetric, String indexTableName, byte[] regionStartKey, byte[] regionEndKey,
    String regionEncodedName, String quantizationType, ProductQuantization pqCodebook) {
    this.dimension = dimension;
    this.m = m;
    this.efConstruction = efConstruction;
    this.alpha = alpha;
    this.distanceMetric = distanceMetric;
    this.indexTableName = indexTableName;
    this.regionStartKey = regionStartKey;
    this.regionEndKey = regionEndKey;
    this.regionEncodedName = regionEncodedName;
    this.quantizationType = quantizationType;
    this.pqCodebook = pqCodebook;
    this.similarityFunction = HnswIndexManager.resolveSimilarityFunction(distanceMetric);
    this.vectorValues = new HnswIndexManager.ConcurrentRandomAccessVectorValues(dimension);
    this.graphBuilder = new GraphIndexBuilder(vectorValues, similarityFunction, m, efConstruction,
      alpha, HnswIndexManager.DEFAULT_NEIGHBOR_OVERFLOW, true);
  }

  @VisibleForTesting
  public GraphIndexBuilder getGraphBuilder() {
    return graphBuilder;
  }

  @VisibleForTesting
  public HnswIndexManager.ConcurrentRandomAccessVectorValues getVectorValues() {
    return vectorValues;
  }

  @VisibleForTesting
  public Map<Integer, byte[]> getOrdinalToKey() {
    return ordinalToKey;
  }

  @VisibleForTesting
  public int getTotalRowCount() {
    return totalRowCount;
  }

  @VisibleForTesting
  public int getVectorColumnIndex() {
    return vectorColumnIndex;
  }

  @VisibleForTesting
  public void setVectorColumnIndex(int index) {
    this.vectorColumnIndex = index;
  }

  @VisibleForTesting
  public byte[] getRegionStartKey() {
    return regionStartKey;
  }

  @VisibleForTesting
  public byte[] getRegionEndKey() {
    return regionEndKey;
  }

  @VisibleForTesting
  public String getRegionEncodedName() {
    return regionEncodedName;
  }

  @VisibleForTesting
  public ProductQuantization getPqCodebook() {
    return pqCodebook;
  }

  @VisibleForTesting
  public void setWriter(DirectHTableWriter writer) {
    this.writer = writer;
  }

  @VisibleForTesting
  public void setConnection(Connection connection) {
    this.connection = connection;
  }
}
