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
package org.apache.phoenix.parse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.IndexConsistency;

import org.apache.phoenix.thirdparty.com.google.common.collect.ArrayListMultimap;
import org.apache.phoenix.thirdparty.com.google.common.collect.ListMultimap;

public class CreateIndexStatement extends SingleTableStatement {
  private final TableName indexTableName;
  private final IndexKeyConstraint indexKeyConstraint;
  private final List<ColumnName> includeColumns;
  private final List<ParseNode> splitNodes;
  private final ListMultimap<String, Pair<String, Object>> props;
  private final boolean ifNotExists;
  private final IndexType indexType;
  private final boolean async;
  private final Map<String, UDFParseNode> udfParseNodes;
  private final ParseNode where;
  private final IndexConsistency indexConsistency;
  private final VectorIndexParams vectorIndexParams;

  /** Encapsulates configuration and metadata parameters for vector indexes. */
  public static class VectorIndexParams {
    private final String algorithm;
    private final String metric;
    private final Integer dimension;
    private final Integer lists;
    private final Integer sampleSize;
    private final Integer hnswM;
    private final Integer hnswEfConstruction;
    private final Double hnswAlpha;
    private final String quantizationType;
    private final Integer pqSegments;
    private final Integer pqTrainingSize;

    public VectorIndexParams(String algorithm, String metric, Integer dimension, Integer lists,
      Integer sampleSize, Integer hnswM, Integer hnswEfConstruction, Double hnswAlpha,
      String quantizationType, Integer pqSegments, Integer pqTrainingSize) {
      this.algorithm = algorithm;
      this.metric = metric;
      this.dimension = dimension;
      this.lists = lists;
      this.sampleSize = sampleSize;
      this.hnswM = hnswM;
      this.hnswEfConstruction = hnswEfConstruction;
      this.hnswAlpha = hnswAlpha;
      this.quantizationType = quantizationType;
      this.pqSegments = pqSegments;
      this.pqTrainingSize = pqTrainingSize;
    }

    public static VectorIndexParams fromProps(ListMultimap<String, Pair<String, Object>> props) {
      if (props == null) {
        return null;
      }
      String algorithm = CreateIndexStatement.getVectorAlgorithm(props);
      String metric = CreateIndexStatement.getVectorMetric(props);
      Integer dimension = CreateIndexStatement.getVectorDimension(props);
      Integer lists = CreateIndexStatement.getVectorLists(props);
      Integer sampleSize = CreateIndexStatement.getVectorSampleSize(props);
      Integer hnswM = CreateIndexStatement.getHnswM(props);
      Integer hnswEfConstruction = CreateIndexStatement.getHnswEfConstruction(props);
      Double hnswAlpha = CreateIndexStatement.getHnswAlpha(props);
      String quantizationType = CreateIndexStatement.getQuantizationType(props);
      Integer pqSegments = CreateIndexStatement.getPqSegments(props);
      Integer pqTrainingSize = CreateIndexStatement.getPqTrainingSize(props);

      if (
        algorithm == null && metric == null && dimension == null && lists == null
          && sampleSize == null && hnswM == null && hnswEfConstruction == null && hnswAlpha == null
          && quantizationType == null && pqSegments == null && pqTrainingSize == null
      ) {
        return null;
      }
      return new VectorIndexParams(algorithm, metric, dimension, lists, sampleSize, hnswM,
        hnswEfConstruction, hnswAlpha, quantizationType, pqSegments, pqTrainingSize);
    }

    public String getAlgorithm() {
      return algorithm;
    }

    public VectorIndexType getType() {
      return VectorIndexType.fromAlgorithm(algorithm);
    }

    public String getMetric() {
      return metric;
    }

    public Integer getDimension() {
      return dimension;
    }

    public Integer getLists() {
      return lists;
    }

    public Integer getSampleSize() {
      return sampleSize;
    }

    public Integer getHnswM() {
      return hnswM;
    }

    public Integer getHnswEfConstruction() {
      return hnswEfConstruction;
    }

    public Double getHnswAlpha() {
      return hnswAlpha;
    }

    public String getQuantizationType() {
      return quantizationType;
    }

    public Integer getPqSegments() {
      return pqSegments;
    }

    public Integer getPqTrainingSize() {
      return pqTrainingSize;
    }

    public void populateTableProps(Map<String, Object> tableProps) {
      if (tableProps == null) {
        return;
      }
      if (algorithm != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_INDEX_ALGORITHM, algorithm);
      }
      if (metric != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_DISTANCE_METRIC, metric);
      }
      if (dimension != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_DIMENSION, dimension);
      }
      if (lists != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_IVF_LISTS, lists);
      }
      if (sampleSize != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_IVF_SAMPLE_SIZE, sampleSize);
      }
      if (hnswM != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_M, hnswM);
      }
      if (hnswEfConstruction != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_EF_CONSTRUCTION, hnswEfConstruction);
      }
      if (hnswAlpha != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_ALPHA, hnswAlpha);
      }
      if (quantizationType != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_QUANTIZATION_TYPE, quantizationType);
      }
      if (pqSegments != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_PQ_SEGMENTS, pqSegments);
      }
      if (pqTrainingSize != null) {
        tableProps.put(PhoenixDatabaseMetaData.VECTOR_PQ_TRAINING_SIZE, pqTrainingSize);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      VectorIndexParams that = (VectorIndexParams) o;
      return Objects.equals(algorithm, that.algorithm) && Objects.equals(metric, that.metric)
        && Objects.equals(dimension, that.dimension) && Objects.equals(lists, that.lists)
        && Objects.equals(sampleSize, that.sampleSize) && Objects.equals(hnswM, that.hnswM)
        && Objects.equals(hnswEfConstruction, that.hnswEfConstruction)
        && Objects.equals(hnswAlpha, that.hnswAlpha)
        && Objects.equals(quantizationType, that.quantizationType)
        && Objects.equals(pqSegments, that.pqSegments)
        && Objects.equals(pqTrainingSize, that.pqTrainingSize);
    }

    @Override
    public int hashCode() {
      return Objects.hash(algorithm, metric, dimension, lists, sampleSize, hnswM,
        hnswEfConstruction, hnswAlpha, quantizationType, pqSegments, pqTrainingSize);
    }

    public static class Builder {
      private String algorithm;
      private String metric;
      private Integer dimension;
      private Integer lists;
      private Integer sampleSize;
      private Integer hnswM;
      private Integer hnswEfConstruction;
      private Double hnswAlpha;
      private String quantizationType;
      private Integer pqSegments;
      private Integer pqTrainingSize;

      public Builder() {
      }

      public Builder(VectorIndexParams copy) {
        if (copy != null) {
          this.algorithm = copy.algorithm;
          this.metric = copy.metric;
          this.dimension = copy.dimension;
          this.lists = copy.lists;
          this.sampleSize = copy.sampleSize;
          this.hnswM = copy.hnswM;
          this.hnswEfConstruction = copy.hnswEfConstruction;
          this.hnswAlpha = copy.hnswAlpha;
          this.quantizationType = copy.quantizationType;
          this.pqSegments = copy.pqSegments;
          this.pqTrainingSize = copy.pqTrainingSize;
        }
      }

      public Builder setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
        return this;
      }

      public Builder setMetric(String metric) {
        this.metric = metric;
        return this;
      }

      public Builder setDimension(Integer dimension) {
        this.dimension = dimension;
        return this;
      }

      public Builder setLists(Integer lists) {
        this.lists = lists;
        return this;
      }

      public Builder setSampleSize(Integer sampleSize) {
        this.sampleSize = sampleSize;
        return this;
      }

      public Builder setHnswM(Integer hnswM) {
        this.hnswM = hnswM;
        return this;
      }

      public Builder setHnswEfConstruction(Integer hnswEfConstruction) {
        this.hnswEfConstruction = hnswEfConstruction;
        return this;
      }

      public Builder setHnswAlpha(Double hnswAlpha) {
        this.hnswAlpha = hnswAlpha;
        return this;
      }

      public Builder setQuantizationType(String quantizationType) {
        this.quantizationType = quantizationType;
        return this;
      }

      public Builder setPqSegments(Integer pqSegments) {
        this.pqSegments = pqSegments;
        return this;
      }

      public Builder setPqTrainingSize(Integer pqTrainingSize) {
        this.pqTrainingSize = pqTrainingSize;
        return this;
      }

      public VectorIndexParams build() {
        if (
          algorithm == null && metric == null && dimension == null && lists == null
            && sampleSize == null && hnswM == null && hnswEfConstruction == null
            && hnswAlpha == null && quantizationType == null && pqSegments == null
            && pqTrainingSize == null
        ) {
          return null;
        }
        return new VectorIndexParams(algorithm, metric, dimension, lists, sampleSize, hnswM,
          hnswEfConstruction, hnswAlpha, quantizationType, pqSegments, pqTrainingSize);
      }
    }
  }

  public CreateIndexStatement(NamedNode indexTableName, NamedTableNode dataTable,
    IndexKeyConstraint indexKeyConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
    ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
    boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where) {
    this(indexTableName, dataTable, indexKeyConstraint, includeColumns, splits, props, ifNotExists,
      indexType, async, bindCount, udfParseNodes, where, getIndexConsistency(props));
  }

  public CreateIndexStatement(NamedNode indexTableName, NamedTableNode dataTable,
    IndexKeyConstraint indexKeyConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
    ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
    boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where,
    IndexConsistency indexConsistency) {
    this(indexTableName, dataTable, indexKeyConstraint, includeColumns, splits, props, ifNotExists,
      indexType, async, bindCount, udfParseNodes, where, indexConsistency,
      VectorIndexParams.fromProps(props));
  }

  public CreateIndexStatement(NamedNode indexTableName, NamedTableNode dataTable,
    IndexKeyConstraint indexKeyConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
    ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
    boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where,
    String vectorAlgorithm, String vectorMetric, Integer vectorLists, Integer vectorSampleSize) {
    this(indexTableName, dataTable, indexKeyConstraint, includeColumns, splits, props, ifNotExists,
      indexType, async, bindCount, udfParseNodes, where, getIndexConsistency(props),
      new VectorIndexParams.Builder().setAlgorithm(vectorAlgorithm).setMetric(vectorMetric)
        .setLists(vectorLists).setSampleSize(vectorSampleSize).build());
  }

  public CreateIndexStatement(NamedNode indexTableName, NamedTableNode dataTable,
    IndexKeyConstraint indexKeyConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
    ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
    boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where,
    String vectorAlgorithm, String vectorMetric, Integer hnswM, Integer hnswEfConstruction,
    Double hnswAlpha, String quantizationType, Integer pqSegments, Integer pqTrainingSize) {
    this(indexTableName, dataTable, indexKeyConstraint, includeColumns, splits, props, ifNotExists,
      indexType, async, bindCount, udfParseNodes, where, getIndexConsistency(props),
      new VectorIndexParams.Builder().setAlgorithm(vectorAlgorithm).setMetric(vectorMetric)
        .setHnswM(hnswM).setHnswEfConstruction(hnswEfConstruction).setHnswAlpha(hnswAlpha)
        .setQuantizationType(quantizationType).setPqSegments(pqSegments)
        .setPqTrainingSize(pqTrainingSize).build());
  }

  public CreateIndexStatement(NamedNode indexTableName, NamedTableNode dataTable,
    IndexKeyConstraint indexKeyConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
    ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
    boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where,
    IndexConsistency indexConsistency, VectorIndexParams vectorIndexParams) {
    super(dataTable, bindCount);
    this.indexTableName =
      TableName.create(dataTable.getName().getSchemaName(), indexTableName.getName());
    this.indexKeyConstraint =
      indexKeyConstraint == null ? IndexKeyConstraint.EMPTY : indexKeyConstraint;
    this.includeColumns =
      includeColumns == null ? Collections.<ColumnName> emptyList() : includeColumns;
    this.splitNodes = splits == null ? Collections.<ParseNode> emptyList() : splits;
    this.props = props == null ? ArrayListMultimap.<String, Pair<String, Object>> create() : props;
    this.ifNotExists = ifNotExists;
    this.indexType = indexType;
    this.async = async;
    this.udfParseNodes = udfParseNodes;
    this.where = where;
    this.indexConsistency = indexConsistency;
    this.vectorIndexParams =
      vectorIndexParams != null ? vectorIndexParams : VectorIndexParams.fromProps(this.props);
  }

  public CreateIndexStatement(CreateIndexStatement createStmt,
    ListMultimap<String, Pair<String, Object>> finalProps) {
    super(createStmt.getTable(), createStmt.getBindCount());
    this.indexTableName = createStmt.getIndexTableName();
    this.indexKeyConstraint = createStmt.getIndexConstraint();
    this.includeColumns = createStmt.getIncludeColumns();
    this.splitNodes = createStmt.getSplitNodes();
    this.props = finalProps;
    this.ifNotExists = createStmt.ifNotExists();
    this.indexType = createStmt.getIndexType();
    this.async = createStmt.isAsync();
    this.udfParseNodes = createStmt.getUdfParseNodes();
    this.where = createStmt.where;
    this.indexConsistency = createStmt.getIndexConsistency();
    this.vectorIndexParams = createStmt.getVectorIndexParams() != null
      ? createStmt.getVectorIndexParams()
      : VectorIndexParams.fromProps(finalProps);
  }

  public CreateIndexStatement(CreateIndexStatement createStmt,
    VectorIndexParams vectorIndexParams) {
    super(createStmt.getTable(), createStmt.getBindCount());
    this.indexTableName = createStmt.getIndexTableName();
    this.indexKeyConstraint = createStmt.getIndexConstraint();
    this.includeColumns = createStmt.getIncludeColumns();
    this.splitNodes = createStmt.getSplitNodes();
    this.props = createStmt.getProps();
    this.ifNotExists = createStmt.ifNotExists();
    this.indexType = createStmt.getIndexType();
    this.async = createStmt.isAsync();
    this.udfParseNodes = createStmt.getUdfParseNodes();
    this.where = createStmt.where;
    this.indexConsistency = createStmt.getIndexConsistency();
    this.vectorIndexParams = vectorIndexParams;
  }

  public CreateIndexStatement(CreateIndexStatement createStmt, Integer vectorDimension) {
    this(createStmt, new VectorIndexParams.Builder(createStmt.getVectorIndexParams())
      .setDimension(vectorDimension).build());
  }

  public static IndexConsistency
    getIndexConsistency(ListMultimap<String, Pair<String, Object>> props) {
    IndexConsistency indexConsistency = null;
    if (props != null) {
      for (Pair<String, Object> prop : props.get(QueryConstants.ALL_FAMILY_PROPERTIES_KEY)) {
        if (prop != null && "CONSISTENCY".equalsIgnoreCase(prop.getFirst())) {
          Object value = prop.getSecond();
          indexConsistency =
            value == null ? null : IndexConsistency.valueOf(value.toString().toUpperCase());
          break;
        }
      }
    }
    return indexConsistency;
  }

  private static <T> T extractProperty(ListMultimap<String, Pair<String, Object>> props,
    Function<Object, T> parser, String... names) {
    if (props != null) {
      for (Pair<String, Object> prop : props.get(QueryConstants.ALL_FAMILY_PROPERTIES_KEY)) {
        if (prop != null && prop.getFirst() != null) {
          for (String name : names) {
            if (name.equalsIgnoreCase(prop.getFirst())) {
              Object val = prop.getSecond();
              return val == null ? null : parser.apply(val);
            }
          }
        }
      }
    }
    return null;
  }

  private static Integer parseInteger(Object val) {
    if (val instanceof Number) {
      return ((Number) val).intValue();
    } else if (val != null) {
      try {
        return Integer.parseInt(val.toString().trim());
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }

  private static Double parseDouble(Object val) {
    if (val instanceof Number) {
      return ((Number) val).doubleValue();
    } else if (val != null) {
      try {
        return Double.parseDouble(val.toString().trim());
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }

  private static String parseSanitizedString(Object val) {
    if (val != null) {
      String s = val.toString().trim();
      return s.replaceAll("^'|'$", "").trim();
    }
    return null;
  }

  public static String getVectorAlgorithm(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, Object::toString, "ALGORITHM", "VECTOR_INDEX_ALGORITHM");
  }

  public static String getVectorMetric(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, Object::toString, "METRIC", "VECTOR_DISTANCE_METRIC",
      "DISTANCE_METRIC");
  }

  public static Integer getVectorLists(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "LISTS", "VECTOR_IVF_LISTS",
      "IVF_LISTS");
  }

  public static Integer getVectorSampleSize(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "SAMPLE_SIZE",
      "VECTOR_IVF_SAMPLE_SIZE", "IVF_SAMPLE_SIZE");
  }

  public static Integer getVectorDimension(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "DIMENSION",
      "VECTOR_DIMENSION");
  }

  public static boolean hasProperty(ListMultimap<String, Pair<String, Object>> props,
    String... propertyNames) {
    if (props != null) {
      for (Pair<String, Object> prop : props.get(QueryConstants.ALL_FAMILY_PROPERTIES_KEY)) {
        if (prop != null && prop.getFirst() != null) {
          for (String name : propertyNames) {
            if (name.equalsIgnoreCase(prop.getFirst())) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  public static Integer getHnswM(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "M", "HNSW_M",
      "VECTOR_HNSW_M");
  }

  public static Integer getHnswEfConstruction(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "EF_CONSTRUCTION",
      "HNSW_EF_CONSTRUCTION", "VECTOR_HNSW_EF_CONSTRUCTION");
  }

  public static Double getHnswAlpha(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseDouble, "ALPHA", "HNSW_ALPHA",
      "VECTOR_HNSW_ALPHA");
  }

  public static String getQuantizationType(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseSanitizedString, "QUANTIZATION",
      "QUANTIZATION_TYPE", "VECTOR_QUANTIZATION_TYPE");
  }

  public static Integer getPqSegments(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "PQ_SEGMENTS",
      "VECTOR_PQ_SEGMENTS");
  }

  public static Integer getPqTrainingSize(ListMultimap<String, Pair<String, Object>> props) {
    return extractProperty(props, CreateIndexStatement::parseInteger, "PQ_TRAINING_SIZE",
      "VECTOR_PQ_TRAINING_SIZE");
  }

  public IndexKeyConstraint getIndexConstraint() {
    return indexKeyConstraint;
  }

  public List<ColumnName> getIncludeColumns() {
    return includeColumns;
  }

  public TableName getIndexTableName() {
    return indexTableName;
  }

  public List<ParseNode> getSplitNodes() {
    return splitNodes;
  }

  public ListMultimap<String, Pair<String, Object>> getProps() {
    return props;
  }

  public boolean ifNotExists() {
    return ifNotExists;
  }

  public IndexType getIndexType() {
    return indexType;
  }

  public boolean isAsync() {
    return async;
  }

  public Map<String, UDFParseNode> getUdfParseNodes() {
    return udfParseNodes;
  }

  public ParseNode getWhere() {
    return where;
  }

  public IndexConsistency getIndexConsistency() {
    return indexConsistency;
  }

  public VectorIndexParams getVectorIndexParams() {
    return vectorIndexParams;
  }

  public String getVectorAlgorithm() {
    return vectorIndexParams != null ? vectorIndexParams.getAlgorithm() : null;
  }

  public VectorIndexType getVectorIndexType() {
    return vectorIndexParams != null ? vectorIndexParams.getType() : null;
  }

  public String getVectorMetric() {
    return vectorIndexParams != null ? vectorIndexParams.getMetric() : null;
  }

  public Integer getVectorLists() {
    return vectorIndexParams != null ? vectorIndexParams.getLists() : null;
  }

  public Integer getVectorSampleSize() {
    return vectorIndexParams != null ? vectorIndexParams.getSampleSize() : null;
  }

  public Integer getVectorDimension() {
    return vectorIndexParams != null ? vectorIndexParams.getDimension() : null;
  }

  public Integer getHnswM() {
    return vectorIndexParams != null ? vectorIndexParams.getHnswM() : null;
  }

  public Integer getHnswEfConstruction() {
    return vectorIndexParams != null ? vectorIndexParams.getHnswEfConstruction() : null;
  }

  public Double getHnswAlpha() {
    return vectorIndexParams != null ? vectorIndexParams.getHnswAlpha() : null;
  }

  public String getQuantizationType() {
    return vectorIndexParams != null ? vectorIndexParams.getQuantizationType() : null;
  }

  public Integer getPqSegments() {
    return vectorIndexParams != null ? vectorIndexParams.getPqSegments() : null;
  }

  public Integer getPqTrainingSize() {
    return vectorIndexParams != null ? vectorIndexParams.getPqTrainingSize() : null;
  }
}
