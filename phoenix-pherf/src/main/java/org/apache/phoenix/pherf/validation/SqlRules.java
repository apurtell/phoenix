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
package org.apache.phoenix.pherf.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.parse.AndParseNode;
import org.apache.phoenix.parse.BindParseNode;
import org.apache.phoenix.parse.BindableStatement;
import org.apache.phoenix.parse.ColumnDef;
import org.apache.phoenix.parse.ColumnName;
import org.apache.phoenix.parse.ColumnParseNode;
import org.apache.phoenix.parse.CreateTableStatement;
import org.apache.phoenix.parse.DeleteStatement;
import org.apache.phoenix.parse.NamedTableNode;
import org.apache.phoenix.parse.ParseNode;
import org.apache.phoenix.parse.SQLParser;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.parse.TableName;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.util.SchemaUtil;

/**
 * Performs the SQL validation phase using the Phoenix parser without requiring a database
 * connection. This encompasses tracking the tables created by the DDL statements in a run and
 * parsing SQL statements along with their placeholder tokens.
 */
final class SqlRules {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\[.*?\\]");

  private SqlRules() {
  }

  /**
   * Represents a table defined by a CREATE TABLE statement, recording its column definitions and
   * its ordered primary key components.
   */
  static final class Table {
    final String name;
    final Map<String, PDataType> columns = new LinkedHashMap<>();
    final List<String> key = new ArrayList<>();
    final List<SortOrder> order = new ArrayList<>();

    private Table(String name) {
      this.name = name;
    }

    /**
     * Returns the column name from this table that matches the specified name either exactly or
     * case-insensitively, or null if no matching column exists.
     */
    String column(String name) {
      if (columns.containsKey(name)) {
        return name;
      }
      for (String column : columns.keySet()) {
        if (column.equalsIgnoreCase(name)) {
          return column;
        }
      }
      return null;
    }
  }

  /**
   * Collects the tables defined by the DDL statements of a run, indexed by their normalized full
   * table names. When multiple definitions share a name, the first definition takes precedence.
   */
  static final class Tables {
    private final Map<String, Table> tables = new HashMap<>();

    /**
     * Parses a DDL string and registers the table if the statement is a valid CREATE TABLE
     * statement. Any other statement type or unparseable SQL string is ignored without adding a
     * table.
     */
    void add(String ddl) {
      BindableStatement statement;
      try {
        statement = new SQLParser(ddl).parseStatement();
      } catch (Exception e) {
        return;
      }
      if (!(statement instanceof CreateTableStatement)) {
        return;
      }
      CreateTableStatement create = (CreateTableStatement) statement;
      Table table = new Table(name(create.getTableName()));
      if (tables.containsKey(table.name)) {
        return;
      }
      for (ColumnDef column : create.getColumnDefs()) {
        table.columns.put(column.getColumnDefName().getColumnName(), column.getDataType());
        if (column.isPK()) {
          table.key.add(column.getColumnDefName().getColumnName());
          table.order.add(column.getSortOrder());
        }
      }
      if (create.getPrimaryKeyConstraint() != null) {
        for (Pair<ColumnName, SortOrder> column : create.getPrimaryKeyConstraint()
          .getColumnNames()) {
          table.key.add(column.getFirst().getColumnName());
          table.order.add(column.getSecond());
        }
      }
      tables.put(table.name, table);
    }

    /**
     * Looks up the table definition corresponding to a scenario table name, or returns null if the
     * DDL does not define that table.
     */
    Table get(String tableName) {
      return tableName == null ? null : tables.get(SchemaUtil.normalizeFullTableName(tableName));
    }
  }

  private static String name(TableName table) {
    return SchemaUtil.getTableName(table.getSchemaName(), table.getTableName());
  }

  /**
   * Represents a SQL statement in which every placeholder backed by a rule has been replaced with a
   * numbered bind parameter.
   */
  static final class Statement {
    /** Holds the transformed SQL statement text containing the substituted bind parameters. */
    String text;
    /** Maps each sequential bind parameter number to its corresponding rule name. */
    final Map<Integer, String> binds = new HashMap<>();
    /**
     * Records each placeholder that matches a rule only when compared case-insensitively, mapped to
     * the canonical rule name.
     */
    final Map<String, String> caseOnly = new LinkedHashMap<>();
    /** Tracks the total number of bind parameters generated for each rule name. */
    final Map<String, Integer> counts = new LinkedHashMap<>();

    private Statement() {
    }

    /**
     * Returns the unique rule name that appears as exactly two bind parameters to represent a range
     * placeholder, or null if there is not a single such rule.
     */
    String range() {
      String range = null;
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        if (entry.getValue() == 2) {
          if (range != null) {
            return null;
          }
          range = entry.getKey();
        }
      }
      return range;
    }
  }

  /**
   * Replaces each rule-backed placeholder with a sequential ":n" bind parameter in text appearance
   * order, matching how the execution engine binds parameters at runtime. A placeholder that
   * matches a rule only in a different letter case is also assigned a bind parameter so that a
   * single case-mismatch defect produces only one validation finding. Bracketed expressions that do
   * not correspond to any rule, such as an array index or a JSON path, are left unchanged.
   * @param rule  resolves a placeholder to its exact rule name, or returns null if none matches.
   * @param other resolves a placeholder with no exact match to a rule name in another case, or
   *              returns null if none matches.
   */
  static Statement substitute(String sql, Function<String, String> rule,
    Function<String, String> other) {
    StringBuffer text = new StringBuffer();
    Statement statement = new Statement();
    Matcher m = PLACEHOLDER.matcher(sql);
    while (m.find()) {
      String placeholder = m.group(0).substring(1, m.group(0).length() - 1);
      String name = rule.apply(placeholder);
      if (name == null) {
        name = other.apply(placeholder);
        if (name != null) {
          statement.caseOnly.put(placeholder, name);
        }
      }
      if (name == null) {
        m.appendReplacement(text, Matcher.quoteReplacement(m.group(0)));
      } else {
        statement.binds.put(statement.binds.size() + 1, name);
        statement.counts.merge(name, 1, Integer::sum);
        m.appendReplacement(text, ":" + statement.binds.size());
      }
    }
    m.appendTail(text);
    statement.text = text.toString();
    return statement;
  }

  /**
   * Extracts and returns the WHERE clause parse tree from a SELECT or DELETE statement, or returns
   * null if the statement has no WHERE clause or is of another statement type.
   */
  static ParseNode where(BindableStatement statement) {
    if (statement instanceof SelectStatement) {
      return ((SelectStatement) statement).getWhere();
    }
    if (statement instanceof DeleteStatement) {
      return ((DeleteStatement) statement).getWhere();
    }
    return null;
  }

  /**
   * Returns the normalized name of the single target table referenced by a SELECT or DELETE
   * statement, or null if the statement does not reference a single named table.
   */
  static String table(BindableStatement statement) {
    if (
      statement instanceof SelectStatement
        && ((SelectStatement) statement).getFrom() instanceof NamedTableNode
    ) {
      return name(((NamedTableNode) ((SelectStatement) statement).getFrom()).getName());
    }
    if (statement instanceof DeleteStatement) {
      return name(((DeleteStatement) statement).getTable().getName());
    }
    return null;
  }

  /**
   * Flattens and returns the top-level AND conjuncts comprising the specified condition parse node.
   */
  static List<ParseNode> conjuncts(ParseNode node) {
    List<ParseNode> conjuncts = new ArrayList<>();
    if (node instanceof AndParseNode) {
      for (ParseNode child : node.getChildren()) {
        conjuncts.addAll(conjuncts(child));
      }
    } else if (node != null) {
      conjuncts.add(node);
    }
    return conjuncts;
  }

  /**
   * Traverses the parse tree rooted at the given node and returns all of its nodes in depth-first
   * order.
   */
  static List<ParseNode> nodes(ParseNode node) {
    List<ParseNode> nodes = new ArrayList<>();
    if (node != null) {
      nodes.add(node);
      for (ParseNode child : node.getChildren()) {
        nodes.addAll(nodes(child));
      }
    }
    return nodes;
  }

  /**
   * Returns the integer bind parameter number for a bind parse node, or 0 if the node is not a
   * numbered bind node.
   */
  static int bind(ParseNode node) {
    if (!(node instanceof BindParseNode)) {
      return 0;
    }
    try {
      return Integer.parseInt(((BindParseNode) node).getName());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Returns the column name referenced by a column parse node, or null if the given node is not a
   * column node.
   */
  static String column(ParseNode node) {
    return node instanceof ColumnParseNode ? ((ColumnParseNode) node).getName() : null;
  }

  /**
   * Determines whether the parse subtree contains at least one bind parameter backed by a rule in
   * the given statement.
   */
  static boolean hasBind(ParseNode node, Statement statement) {
    for (ParseNode child : nodes(node)) {
      if (statement.binds.containsKey(bind(child))) {
        return true;
      }
    }
    return false;
  }
}
