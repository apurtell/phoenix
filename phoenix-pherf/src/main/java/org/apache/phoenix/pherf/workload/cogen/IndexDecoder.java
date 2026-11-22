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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.rules.DataValue;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.pherf.rules.IndexedDataGenerator;
import org.apache.phoenix.pherf.rules.IndexedRule;
import org.apache.phoenix.pherf.rules.RuleBasedDataGenerator;
import org.apache.phoenix.pherf.rules.RulesApplier;

/**
 * Derives column values for a co-generated scenario solely from the local row index. Writers bind
 * row parameters from this decoder while readers resolve statement placeholders through it,
 * allowing readers to reconstruct the exact keys written by writers without sharing key state.
 * <p>
 * The global index is computed as the configured offset plus the local index. When a cluster limit
 * L is smaller than the block size B, the leading primary key column is evaluated at the global
 * index offset + c(i), where c(i) = (i / B) * L + (i mod B) / ceil(B / L). Consequently, the
 * leading key takes at most L values within each aligned window of B indexes, and c(i) is at most
 * i. All other columns are evaluated at offset + i. To maintain row uniqueness under clustering,
 * the primary key requires a subsequent column that is unique for each index, such as a SEQUENTIAL
 * column.
 * <p>
 * Each column name resolves its generation rule once using the writer resolution path, and the
 * decoder caches only that rule. A generator class that does not implement
 * {@link IndexedDataGenerator} produces its own sequence of values: row generation and statement
 * substitution each maintain a dedicated generator instance, mirroring how writers and readers each
 * maintain their own rules applier on the standard rule path.
 * <p>
 * Statement literals reproduce the exact values bound by the writer. DATE and TIMESTAMP values are
 * formatted as UTC text with milliseconds: the writer binds them using {@link #dateFormat()}, and
 * the statement embeds a typed literal that Phoenix parses in GMT. Binary values are rendered as
 * hexadecimal literals encoding the exact bytes bound by the writer.
 */
public final class IndexDecoder {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\[.*?\\]");

  /** Holds the resolved rule and data generators that supply values for a single column name. */
  private static final class Source {
    final Column rule;
    final long seed;
    final IndexedDataGenerator indexed;
    /**
     * Maintains separate generator instances for row construction and statement substitution when
     * the configured generator class does not implement the indexed interface.
     */
    final RuleBasedDataGenerator rows;
    final RuleBasedDataGenerator statements;

    Source(Column rule, long seed, IndexedDataGenerator indexed, RuleBasedDataGenerator rows,
      RuleBasedDataGenerator statements) {
      this.rule = rule;
      this.seed = seed;
      this.indexed = indexed;
      this.rows = rows;
      this.statements = statements;
    }
  }

  private final RulesApplier rules;
  private final Scenario scenario;
  private final CoGenerator.Settings settings;
  private final String leadingKey;
  private final Map<String, Source> sources = new ConcurrentHashMap<>();

  /**
   * @param settings   Specifies the validated co-generation settings for the scenario.
   * @param leadingKey Identifies the leading primary key column, or is null if none applies; this
   *                   parameter takes effect only when a cluster limit is configured.
   */
  public IndexDecoder(RulesApplier rules, Scenario scenario, CoGenerator.Settings settings,
    String leadingKey) {
    this.rules = rules;
    this.scenario = scenario;
    this.settings = settings;
    this.leadingKey = leadingKey;
  }

  /**
   * Returns a date formatter configured with the default value pattern in the UTC time zone for
   * writer parameter binding.
   */
  public static SimpleDateFormat dateFormat() {
    SimpleDateFormat format = new SimpleDateFormat(PherfConstants.DEFAULT_DATE_PATTERN);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format;
  }

  /**
   * Computes the data value of a Phoenix metadata column for a given local index as bound by the
   * writer.
   */
  public DataValue value(Column column, long index) {
    Source source = source(column);
    return value(source, source.rows, column.getName(), index);
  }

  /** Computes all column values for a single row at the given local index, in column order. */
  public List<DataValue> row(List<Column> columns, long index) {
    List<DataValue> values = new ArrayList<>(columns.size());
    for (Column column : columns) {
      values.add(value(column, index));
    }
    return values;
  }

  /**
   * Replaces every [NAME] placeholder backed by a generation rule with the SQL literal for a single
   * local index, following the substitution semantics of
   * {@link org.apache.phoenix.pherf.configuration.Query} and enclosing CHAR and VARCHAR values in
   * the quotes used by Query. The index is drawn once from draw at the first placeholder that
   * resolves to a rule, ensuring that every substituted value in the statement comes from the same
   * row. If a statement has no placeholder with a rule, draw is not invoked.
   */
  public String statement(String sql, LongSupplier draw) {
    String ret = sql;
    boolean drawn = false;
    long index = 0;
    Matcher m = PLACEHOLDER.matcher(ret);
    while (m.find()) {
      String dynamicField = m.group(0).replace("[", "").replace("]", "");
      Column dynamicColumn = rules.getRule(dynamicField, scenario);
      // Bracketed expressions without a matching rule, such as a JSON path like info[5].name, are
      // left untouched in the statement.
      if (dynamicColumn != null && dynamicColumn.getType() != null) {
        if (!drawn) {
          index = draw.getAsLong();
          drawn = true;
        }
        Column column = new Column();
        column.setName(dynamicField);
        column.setType(dynamicColumn.getType());
        Source source = source(column);
        ret = ret.replace("[" + dynamicField + "]", literal(dynamicColumn.getType(),
          value(source, source.statements, dynamicField, index).getValue()));
      }
    }
    return ret;
  }

  /**
   * Formats a generated value as a SQL literal that matches the writer parameter binding for the
   * given data type.
   */
  private static String literal(DataTypeMapping type, String value) {
    switch (type) {
      case CHAR:
      case VARCHAR:
        return "'" + value + "'";
      case DATE:
      case TIMESTAMP:
        return type + " '" + value + "'";
      case VARBINARY:
        return "X'" + Bytes.toHex(value.getBytes()) + "'";
      case VARBINARY_ENCODED:
        return "X'" + value + "'";
      default:
        return value;
    }
  }

  private DataValue value(Source source, RuleBasedDataGenerator plain, String name, long index) {
    long g = settings.offset + (name.equals(leadingKey) ? cluster(index) : index);
    if (IndexedRule.isNull(source.rule, g, source.seed)) {
      return new DataValue(source.rule.getType(), "");
    }
    if (source.indexed != null) {
      return source.indexed.valueAt(g, source.seed);
    }
    return plain.getDataValue();
  }

  /**
   * Computes the clustered local index c(i) for the leading primary key column, or returns i when
   * clustering is not active.
   */
  private long cluster(long index) {
    int blockSize = settings.blockSize;
    if (settings.clusterLimit == null || settings.clusterLimit >= blockSize) {
      return index;
    }
    long clusterLimit = settings.clusterLimit;
    long perValue = (blockSize + clusterLimit - 1) / clusterLimit;
    return (index / blockSize) * clusterLimit + (index % blockSize) / perValue;
  }

  /**
   * Resolves and caches the value source for a column name on first access. Because the rules
   * applier is not thread-safe, rule resolution synchronizes on the rules applier instance.
   */
  private Source source(Column column) {
    Source source = sources.get(column.getName());
    if (source != null) {
      return source;
    }
    synchronized (rules) {
      source = sources.get(column.getName());
      if (source == null) {
        Column rule = rules.getRuleForColumn(scenario, column);
        long seed = IndexHash.columnSeed(settings.seed, column.getName());
        RuleBasedDataGenerator plain =
          rule.getGeneratorClass() == null ? null : RulesApplier.newGenerator(rule);
        IndexedDataGenerator indexed = plain == null ? new IndexedRule(rule)
          : plain instanceof IndexedDataGenerator ? (IndexedDataGenerator) plain
          : null;
        source = indexed != null
          ? new Source(rule, seed, indexed, null, null)
          : new Source(rule, seed, null, plain, RulesApplier.newGenerator(rule));
        sources.put(column.getName(), source);
      }
      return source;
    }
  }
}
