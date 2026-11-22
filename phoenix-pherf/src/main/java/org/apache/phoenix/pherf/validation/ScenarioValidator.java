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

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.parse.BetweenParseNode;
import org.apache.phoenix.parse.BindableStatement;
import org.apache.phoenix.parse.ComparisonParseNode;
import org.apache.phoenix.parse.EqualParseNode;
import org.apache.phoenix.parse.GreaterThanOrEqualParseNode;
import org.apache.phoenix.parse.GreaterThanParseNode;
import org.apache.phoenix.parse.InListParseNode;
import org.apache.phoenix.parse.LessThanOrEqualParseNode;
import org.apache.phoenix.parse.LessThanParseNode;
import org.apache.phoenix.parse.NotEqualParseNode;
import org.apache.phoenix.parse.ParseNode;
import org.apache.phoenix.parse.RowValueConstructorParseNode;
import org.apache.phoenix.parse.SQLParser;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Ddl;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.exception.FileLoaderException;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.schema.SchemaReader;
import org.apache.phoenix.pherf.util.ResourceList;
import org.apache.phoenix.pherf.validation.SqlRules.Statement;
import org.apache.phoenix.pherf.validation.SqlRules.Table;
import org.apache.phoenix.pherf.validation.ValidationReport.Rule;
import org.apache.phoenix.pherf.workload.QueryExecutor;
import org.apache.phoenix.pherf.workload.cogen.CoGenerator;
import org.apache.phoenix.pherf.workload.cogen.IndexDecoder;
import org.apache.phoenix.pherf.workload.cogen.RowCursor;
import org.apache.phoenix.pherf.workload.cogen.Walk;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.util.SchemaUtil;

/**
 * Validates scenario configurations offline by inspecting the scenario files, their referenced
 * profiles, and the DDL statements for a run, reporting all rule violations in a single pass.
 * Validation operates without opening a database connection by leveraging the configuration parser,
 * data rules, co-generation settings, walk strategy, row cursor, index decoder, and Phoenix SQL
 * parser, all of which function independently of a live cluster.
 * <p>
 * Every validation rule is evaluated against each target object to which it applies. Checks that
 * mirror runtime validations report their first failure per object using the exact runtime message.
 * When a rule depends on the outcome of a prerequisite rule, it is skipped if that prerequisite
 * fails, ensuring that a single defect produces only a single finding. Similarly, rules that
 * require a table's DDL definition are skipped if the run's DDL does not create that table.
 * <p>
 * Because the validator has no visibility into the database, a run configured without a data load
 * is assumed to read rows populated by a prior load. The validator verifies read operations at that
 * expected starting state, though it cannot confirm whether the earlier load completed in full,
 * used identical settings, or had its rows altered by another writer.
 */
public final class ScenarioValidator {
  /** The Pherf exit code returned when a validation rule fails, matching sysexits EX_DATAERR. */
  public static final int EXIT_CODE = 65;

  private final ValidationReport report = new ValidationReport();
  private final SqlRules.Tables tables = new SqlRules.Tables();
  private final List<Parsed> parsed = new ArrayList<>();
  /** Tracks whether any scenario in the validation run is missing a name attribute. */
  private boolean unnamed;

  /**
   * Represents a successfully parsed scenario file and retains its structural findings until
   * semantic validation rules are executed.
   */
  private static final class Parsed {
    private final String file;
    private final DataModel model;
    private final ValidationReport findings;
    /** Holds the named scenarios from this file; subsequent validation rules run only on these. */
    private final List<Scenario> scenarios = new ArrayList<>();

    private Parsed(String file, DataModel model, ValidationReport findings) {
      this.file = file;
      this.model = model;
      this.findings = findings;
    }
  }

  private ScenarioValidator() {
  }

  /**
   * Validates all scenario files matching the given pattern, resolving resources in the same manner
   * as the -scenarioFile command-line option.
   * @param schemaPattern the pattern used to locate schema files applied by the run, or null if no
   *                      schema files are used
   * @throws FileLoaderException when no scenario files match the pattern
   */
  public static ValidationReport validate(String scenarioPattern, String schemaPattern)
    throws Exception {
    return validate(scenarioFiles(scenarioPattern), schemaPattern);
  }

  /**
   * Validates a collection of scenario files together as a single execution run.
   * @param schemaPattern the pattern used to locate schema files applied by the run, or null if no
   *                      schema files are used
   * @throws FileLoaderException when no schema files match the pattern, matching runtime behavior
   */
  public static ValidationReport validate(Collection<Path> files, String schemaPattern)
    throws Exception {
    // Instantiate the runtime schema reader without a database connection.
    SchemaReader schema = schemaPattern == null ? null : new SchemaReader(null, schemaPattern);
    return validate(files, schema, new HashMap<>());
  }

  /**
   * Validates all scenario files matching the given pattern as the pre-run validation gate of an
   * execution, populating the parsed data models for reuse by the run so that the files do not need
   * to be parsed a second time.
   * @param schema the schema reader for the run, or null when the run applies no schema
   * @param models receives the parsed data model for each scenario file that parses successfully,
   *               in file order; when the validation report contains no findings, each model is
   *               identical to the model produced by the runtime configuration parser
   * @throws FileLoaderException when no scenario files match the pattern
   */
  public static ValidationReport validate(String scenarioPattern, SchemaReader schema,
    Map<Path, DataModel> models) throws Exception {
    return validate(scenarioFiles(scenarioPattern), schema, models);
  }

  /** Resolves the scenario files matching a pattern in the same manner as the runtime parser. */
  private static Collection<Path> scenarioFiles(String scenarioPattern) throws Exception {
    Collection<Path> files =
      new ResourceList(PherfConstants.RESOURCE_SCENARIO).getResourceList(scenarioPattern);
    if (files.isEmpty()) {
      throw new FileLoaderException(
        "Could not load the resource files using the pattern: " + scenarioPattern);
    }
    return files;
  }

  private static ValidationReport validate(Collection<Path> files, SchemaReader schema,
    Map<Path, DataModel> models) throws Exception {
    ScenarioValidator validator = new ScenarioValidator();
    if (schema != null) {
      // Each schema file is applied by the runtime as a single DDL statement.
      for (Path file : schema.getResourceList()) {
        validator.report.addSchemaFile(file.toString());
        validator.tables.add(schema.resourceToString(file));
      }
    }
    Map<Path, XmlStructure.Walked> profiles = new HashMap<>();
    List<ValidationReport> perFile = new ArrayList<>();
    for (Path file : files) {
      ValidationReport findings = new ValidationReport();
      perFile.add(findings);
      validator.structure(file, findings, profiles, models);
    }
    for (Parsed file : validator.parsed) {
      for (Scenario scenario : file.scenarios) {
        check(file.findings, file.file, "scenario " + scenario.getName(),
          () -> new ScenarioRules(validator, file, scenario).run());
      }
    }
    for (ValidationReport findings : perFile) {
      validator.report.addAll(findings);
    }
    validator.crossScenario();
    return validator.report;
  }

  /**
   * Executes the validation checks for a single configuration object. Runtime model getters throw
   * an unchecked exception when a required attribute (such as a scenario name) is missing; catching
   * that exception records a finding while allowing validation to proceed on remaining objects.
   * @return false when the checks terminated early due to an unchecked exception
   */
  private static boolean check(ValidationReport findings, String file, String where,
    Runnable checks) {
    try {
      checks.run();
      return true;
    } catch (RuntimeException e) {
      findings.add(Rule.XML_VALUE, file, null, where,
        "a value that the run needs is missing or not valid, so the run fails here: " + e);
      return false;
    }
  }

  /**
   * Evaluates structural and profile rules for a single scenario file, advancing the file to the
   * semantic validation phase if it parses cleanly.
   */
  private void structure(Path path, ValidationReport findings,
    Map<Path, XmlStructure.Walked> profiles, Map<Path, DataModel> models) {
    String file = path.toString();
    report.addFile(file);
    // Skip further validation if the file fails structural XML inspection, as the recorded
    // findings already explain why the parser cannot read it.
    if (
      XmlStructure.walk(XmlStructure.scenario(), path, file, findings) != XmlStructure.Walked.OK
    ) {
      return;
    }
    DataModel model;
    try {
      model = XMLConfigParser.parse(path);
    } catch (Exception e) {
      findings.add(Rule.XML_PARSE, file, null, XmlStructure.scenario().root(),
        "the parser rejects the file: " + e);
      return;
    }
    // Propagate each column's data type to its nested data values, matching runtime parser setup.
    XMLConfigParser.updateDataValueType(model);
    models.put(path, model);
    Parsed result = new Parsed(file, model, findings);
    int index = 0;
    for (Scenario scenario : model.getScenarios()) {
      index++;
      String element = XmlStructure.scenario().root() + "/scenarios/scenario[" + index + "]";
      if (!check(findings, file, element + "/@name", scenario::getName)) {
        unnamed = true;
        continue;
      }
      result.scenarios.add(scenario);
      int position = 0;
      for (QuerySet querySet : scenario.getQuerySet()) {
        position++;
        if (querySet.getProfile() != null) {
          profile(path, scenario, position, querySet, findings, profiles);
        }
      }
      if (scenario.getPreScenarioDdls() != null) {
        int ddls = 0;
        for (Ddl ddl : scenario.getPreScenarioDdls()) {
          ddls++;
          check(findings, file, element + "/preScenarioDdls/ddl[" + ddls + "]",
            () -> tables.add(ddl.toString()));
        }
      }
    }
    parsed.add(result);
  }

  /** Validates the workload profile referenced by a single query set. */
  private void profile(Path path, Scenario scenario, int position, QuerySet querySet,
    ValidationReport findings, Map<Path, XmlStructure.Walked> profiles) {
    String file = path.toString();
    Path profile = XMLConfigParser.profilePath(path, querySet.getProfile());
    XmlStructure.Walked walked = profiles.get(profile);
    if (walked == null) {
      report.addFile(profile.toString());
      walked = XmlStructure.walk(XmlStructure.profile(), profile, profile.toString(), findings);
      profiles.put(profile, walked);
    }
    // When structural inspection of a profile fails, those findings already explain the failure;
    // profile resolution errors are then reported only if the query set also defines its own
    // openLoop or mix configuration.
    boolean applies = walked == XmlStructure.Walked.OK || querySet.getOpenLoop() != null
      || querySet.getMix() != null;
    String where = querySetWhere(scenario, position);
    check(findings, file, where, () -> {
      try {
        XMLConfigParser.resolveProfile(scenario, position, querySet, path);
      } catch (IllegalArgumentException e) {
        if (applies) {
          findings.add(Rule.XML_PROFILE, file, null, where, e.getMessage());
        }
      }
    });
  }

  private static String querySetWhere(Scenario scenario, int position) {
    return "scenario " + scenario.getName() + " querySet " + position;
  }

  /**
   * Verifies that the walk step is coprime to the size of a reader's share window so that a single
   * pass visits every index in the window exactly once. Because the walk generator computes coprime
   * steps internally and no user configuration exposes the step directly, this check serves as a
   * safeguard against defects in the walk implementation.
   * @return true when a validation finding is recorded
   */
  static boolean checkWalk(ValidationReport report, String file, String where, long window,
    long step) {
    if (window > 2 && Walk.gcd(step, window) != 1) {
      report.add(Rule.COGEN_WALK, file, null, where,
        "step " + step + " is not coprime to the window of " + window
          + " indexes, so a pass gives some indexes more than once and misses others");
      return true;
    }
    return false;
  }

  /**
   * Evaluates cross-scenario invariants across all co-generated scenarios in the run after per-file
   * validation completes.
   */
  private void crossScenario() {
    List<DataModel> models = new ArrayList<>();
    for (Parsed file : parsed) {
      models.add(file.model);
    }
    // Scenarios with the same name share a single row cursor, meaning a second load under the same
    // name would reset the watermark advanced by the first. Because the runtime lookup inspects
    // every scenario name, this check is skipped if any scenario in the run lacks a name.
    Set<String> names = new HashSet<>();
    for (Parsed file : unnamed ? Collections.<Parsed> emptyList() : parsed) {
      for (Scenario scenario : file.scenarios) {
        if (scenario.getCoGeneration() == null || !names.add(scenario.getName())) {
          continue;
        }
        try {
          CoGenerator.model(models, scenario);
        } catch (IllegalArgumentException e) {
          report.add(Rule.COGEN_WATERMARK, file.file, null, "scenario " + scenario.getName(),
            e.getMessage() + ". The scenarios share one row cursor, so a second load resets the"
              + " watermark that the first advanced");
        }
      }
    }
    // Ensure that writers targeting the same table occupy disjoint global row index intervals
    // [offset, offset + rows).
    Map<String, List<long[]>> ranges = new LinkedHashMap<>();
    Map<String, List<String>> owners = new LinkedHashMap<>();
    for (Parsed file : parsed) {
      for (Scenario scenario : file.scenarios) {
        if (scenario.getCoGeneration() == null || scenario.getTableName() == null) {
          continue;
        }
        CoGenerator.Settings settings;
        try {
          settings = CoGenerator.Settings.of(scenario);
        } catch (IllegalArgumentException e) {
          continue;
        }
        String where = "scenario " + scenario.getName();
        long end;
        try {
          end = scenario.getWriteParams() != null
            ? Long.MAX_VALUE
            : Math.addExact(settings.offset, scenario.getRowCount());
        } catch (ArithmeticException e) {
          report.add(Rule.COGEN_OFFSETS, file.file, null, where, "offset " + settings.offset
            + " plus rowCount " + scenario.getRowCount() + " overflows a long");
          continue;
        }
        String table = SchemaUtil.normalizeFullTableName(scenario.getTableName());
        List<long[]> written = ranges.computeIfAbsent(table, t -> new ArrayList<>());
        List<String> writers = owners.computeIfAbsent(table, t -> new ArrayList<>());
        for (int i = 0; i < written.size(); i++) {
          long[] other = written.get(i);
          if (settings.offset < other[1] && other[0] < end) {
            report.add(Rule.COGEN_OFFSETS, file.file, null, where,
              "rows " + range(settings.offset, end) + " overlap rows " + range(other[0], other[1])
                + " of " + writers.get(i) + " on table " + table
                + ", so the writers write the same keys");
          }
        }
        written.add(new long[] { settings.offset, end });
        writers.add(where);
      }
    }
  }

  private static String range(long start, long end) {
    return "[" + start + ", " + (end == Long.MAX_VALUE ? "unbounded" : String.valueOf(end)) + ")";
  }

  /** Evaluates the validation rules for a single scenario in model declaration order. */
  private static final class ScenarioRules {
    private final Parsed file;
    private final Scenario scenario;
    private final RulesApplier rules;
    private final Table table;
    private final String where;
    private final boolean cogen;
    private final Set<String> unwritableKeys = new HashSet<>();
    private CoGenerator.Settings settings;

    private ScenarioRules(ScenarioValidator validator, Parsed file, Scenario scenario) {
      this.file = file;
      this.scenario = scenario;
      this.rules = new RulesApplier(file.model);
      this.table = validator.tables.get(scenario.getTableName());
      this.where = "scenario " + scenario.getName();
      this.cogen = scenario.getCoGeneration() != null;
    }

    private void add(Rule rule, String at, String message) {
      file.findings.add(rule, file.file, null, at, message);
    }

    private void run() {
      if (cogen) {
        try {
          settings = CoGenerator.Settings.of(scenario);
        } catch (IllegalArgumentException e) {
          add(Rule.COGEN_SETTINGS, where, e.getMessage());
        }
        if (table != null) {
          writerRules();
        }
      }
      int position = 0;
      for (QuerySet querySet : scenario.getQuerySet()) {
        position++;
        querySet(querySet, position);
      }
    }

    /**
     * Verifies that every primary key column has a writer data mapping rule and that all rows
     * generated within a single writer block receive distinct keys.
     */
    private void writerRules() {
      for (String key : table.key) {
        Column column = new Column();
        column.setName(key);
        String sqlType =
          table.columns.get(key) == null ? "no type" : table.columns.get(key).getSqlTypeName();
        try {
          column.setType(DataTypeMapping.valueOf(sqlType.replace(' ', '_')));
          rules.getRuleForColumn(scenario, column);
        } catch (IllegalArgumentException | IllegalStateException e) {
          unwritableKeys.add(key);
          add(Rule.KEY_COVERAGE, where,
            "primary key column " + key + " (" + sqlType + ") of table " + table.name
              + " has no datamapping rule, so the writer cannot write it: " + e.getMessage());
        }
      }
      if (settings != null && !table.key.isEmpty()) {
        try {
          CoGenerator.checkLeadingKey(scenario, settings, table.key.get(0), table.key.size());
        } catch (IllegalArgumentException | IllegalStateException e) {
          add(Rule.COGEN_WRITER_BLOCKS, where, e.getMessage());
        }
      }
    }

    private void querySet(QuerySet querySet, int position) {
      String at = querySetWhere(scenario, position);
      try {
        QueryExecutor.checkWarmupExecutions(scenario, querySet);
      } catch (IllegalArgumentException e) {
        add(Rule.QUERYSET_WARMUP, at, e.getMessage());
      }
      if (querySet.getOpenLoop() != null) {
        String openLoop = QueryExecutor.openLoopWhere(position, scenario);
        try {
          QueryExecutor.checkOpenLoopRates(openLoop, querySet);
        } catch (IllegalArgumentException e) {
          add(Rule.OPENLOOP_RATE, at, e.getMessage());
        }
        try {
          QueryExecutor.checkArrivalSeed(openLoop, querySet.getOpenLoop());
        } catch (IllegalArgumentException e) {
          add(Rule.OPENLOOP_SEED, at, e.getMessage());
        }
        try {
          QueryExecutor.checkOpenLoopLag(openLoop, querySet.getOpenLoop());
        } catch (IllegalArgumentException e) {
          add(Rule.OPENLOOP_RATE, at, e.getMessage());
        }
      }
      List<Query.Kind> kinds = new ArrayList<>();
      for (int q = 0; q < querySet.getQuery().size(); q++) {
        try {
          kinds.add(querySet.getQuery().get(q).kind(scenario.getName()));
        } catch (IllegalArgumentException e) {
          kinds.add(null);
          add(Rule.QUERY_KIND, at + " query " + (q + 1), e.getMessage());
        }
      }
      if (!kinds.contains(null)) {
        try {
          QueryExecutor.checkMix(scenario, position, querySet);
        } catch (IllegalArgumentException e) {
          add(Rule.OPENLOOP_MIX, at, e.getMessage());
        }
      }
      Set<Query.Kind> draws = new HashSet<>();
      for (int q = 0; q < querySet.getQuery().size(); q++) {
        String sql = querySet.getQuery().get(q).getStatement();
        if (
          sql != null && new StatementRules(this, sql, kinds.get(q), at + " query " + (q + 1)).run()
        ) {
          draws.add(kinds.get(q));
        }
      }
      if (cogen && settings != null) {
        walkRules(querySet, at, draws);
      }
    }

    /**
     * Validates worker read partitions at the beginning of a run when the watermark equals the
     * configured row count, which represents both the state immediately following a completed load
     * and the assumed starting state for a run executed without a load phase.
     */
    private void walkRules(QuerySet querySet, String at, Set<Query.Kind> draws) {
      int min;
      int max;
      if (querySet.getOpenLoop() != null) {
        // Every query in an open-loop query set executes with a single reader worker.
        min = 1;
        max = 1;
      } else {
        try {
          min = querySet.getMinConcurrency();
          max = querySet.getMaxConcurrency();
        } catch (NumberFormatException e) {
          return;
        }
      }
      long rows = scenario.getRowCount();
      long windows = new RowCursor(settings.blockSize, rows).windows(rows);
      if (draws.contains(Query.Kind.SELECT)) {
        shares(at, "SELECT", "committed row", rows, min, max);
      }
      if (draws.contains(Query.Kind.SCAN)) {
        shares(at, "SCAN", "committed block", windows, min, max);
      }
      if (draws.contains(Query.Kind.DELETE) && windows < 1) {
        add(Rule.COGEN_WALK, at,
          "DELETE: no committed block to delete at the start, with rowCount " + rows);
      }
    }

    /**
     * Verifies that every worker across the configured concurrency range receives a non-empty share
     * window and a coprime walk step.
     */
    private void shares(String at, String kind, String item, long count, int min, int max) {
      long walkSeed = IndexHash.columnSeed(settings.seed, "#walk");
      for (int workers = Math.max(1, min); workers <= max; workers++) {
        // Worker 0 is assigned the largest share window, while worker W - 1 receives the smallest.
        for (int worker : new int[] { 0, workers - 1 }) {
          long window = Walk.window(count, worker, workers);
          if (window < 1) {
            add(Rule.COGEN_WALK, at, kind + ": worker " + worker + " of " + workers + " has no "
              + item + " at the start, with " + count + " " + item + "s in all");
            return;
          }
          if (
            settings.walk == WalkStrategy.MULTIPLICATIVE
              && checkWalk(file.findings, file.file, at, window, Walk.step(walkSeed, window))
          ) {
            return;
          }
        }
      }
    }
  }

  /** Evaluates the SQL and co-generation predicate rules for a single query statement. */
  private static final class StatementRules {
    private final ScenarioRules scenario;
    private final String sql;
    private final Query.Kind kind;
    private final String at;
    private int findings;

    private StatementRules(ScenarioRules scenario, String sql, Query.Kind kind, String at) {
      this.scenario = scenario;
      this.sql = sql;
      this.kind = kind;
      this.at = at;
    }

    private void add(Rule rule, String message) {
      findings++;
      scenario.add(rule, at, message);
    }

    /**
     * Executes all statement-level validation rules and returns true if the statement draws a row
     * or block index from the co-generation walk.
     */
    private boolean run() {
      Statement statement = SqlRules.substitute(sql, this::rule, this::caseOnlyRule);
      for (Map.Entry<String, String> entry : statement.caseOnly.entrySet()) {
        add(Rule.PREDICATE_CASING,
          "placeholder [" + entry.getKey() + "] has no rule, and rule " + entry.getValue()
            + " differs only in case, so the run leaves [" + entry.getKey() + "] in the statement");
      }
      BindableStatement parsed;
      try {
        parsed = new SQLParser(statement.text).parseStatement();
      } catch (SQLException e) {
        if (
          scenario.cogen
            && e.getErrorCode() == SQLExceptionCode.VALUE_IN_LIST_NOT_CONSTANT.getErrorCode()
        ) {
          add(Rule.PREDICATE_IN_LIST, "an IN list element is not a constant, and Phoenix needs"
            + " constant elements: " + e.getMessage());
        } else {
          add(Rule.PREDICATE_PARSE, "the statement does not parse after the placeholder"
            + " substitution: " + e.getMessage());
        }
        return false;
      } catch (RuntimeException e) {
        add(Rule.PREDICATE_PARSE,
          "the statement does not parse after the placeholder substitution: " + e);
        return false;
      }
      ParseNode where = SqlRules.where(parsed);
      Table table = scenario.table;
      boolean onTable = table != null && table.name.equals(SqlRules.table(parsed));
      Map<String, String> columns = new LinkedHashMap<>();
      if (onTable) {
        for (ParseNode node : SqlRules.nodes(where)) {
          String name = SqlRules.column(node);
          String column = name == null ? null : table.column(name);
          if (column != null && !column.equals(name) && columns.put(name, column) == null) {
            add(Rule.PREDICATE_CASING, "column \"" + name + "\" names no column of table "
              + table.name + ", and column " + column + " differs only in case");
          }
        }
      }
      // A point read without placeholders does not draw a row index, whereas a range statement
      // always draws a block.
      if (
        !scenario.cogen || kind == null || kind == Query.Kind.SELECT && statement.binds.isEmpty()
      ) {
        return false;
      }
      if (onTable) {
        alignment(where, statement, columns);
      }
      boolean range = kind == Query.Kind.SCAN || kind == Query.Kind.DELETE;
      int forms = findings;
      inLists(where, statement, range);
      rowValues(where, statement, range);
      if (findings == forms && range) {
        rangeBounds(where, statement, columns);
      }
      if (kind == Query.Kind.SELECT && onTable) {
        pointKey(where, statement, columns);
      }
      if (range && findings == 0 && scenario.settings != null) {
        decoderRange(statement);
      }
      return true;
    }

    /**
     * Returns the exact rule name matching a placeholder as substituted at runtime, or null if no
     * typed rule matches.
     */
    private String rule(String placeholder) {
      Column rule = scenario.rules.getRule(placeholder, scenario.scenario);
      return rule != null && rule.getType() != null ? placeholder : null;
    }

    /**
     * Returns the name of a data mapping rule that matches the placeholder only when letter case is
     * ignored, or null if none exists.
     */
    private String caseOnlyRule(String placeholder) {
      List<Column> candidates = new ArrayList<>(scenario.file.model.getDataMappingColumns());
      if (scenario.scenario.getDataOverride() != null) {
        candidates.addAll(scenario.scenario.getDataOverride().getColumn());
      }
      for (Column column : candidates) {
        if (
          column.getName() != null && column.getType() != null
            && column.getName().equalsIgnoreCase(placeholder)
        ) {
          return column.getName();
        }
      }
      return null;
    }

    private String column(ParseNode node, Map<String, String> columns) {
      String name = SqlRules.column(node);
      return columns.containsKey(name) ? columns.get(name) : name;
    }

    private String placeholder(ParseNode node, Statement statement) {
      return statement.binds.get(SqlRules.bind(node));
    }

    /**
     * Verifies that every comparison between a column of the scenario table and a placeholder
     * references the placeholder's own column name. Statements targeting a different table are
     * permitted to compare any column against a placeholder.
     */
    private void alignment(ParseNode where, Statement statement, Map<String, String> columns) {
      BiConsumer<String, String> aligned = (column, placeholder) -> {
        if (!column.equals(placeholder)) {
          add(Rule.KEY_COVERAGE,
            "column " + column + " is compared with placeholder [" + placeholder
              + "], but the decoder binds [" + placeholder + "] to the value of " + placeholder
              + " of the drawn row");
        }
      };
      for (ParseNode node : SqlRules.nodes(where)) {
        List<ParseNode> children = node.getChildren();
        if (
          node instanceof ComparisonParseNode || node instanceof BetweenParseNode
            || node instanceof InListParseNode
        ) {
          for (int i = 1; i < children.size(); i++) {
            pairs(children.get(0), children.get(i), statement, columns, aligned);
          }
          if (node instanceof ComparisonParseNode) {
            pairs(children.get(1), children.get(0), statement, columns, aligned);
          }
        }
      }
    }

    /**
     * Invokes the consumer for each column-and-placeholder pair compared by an expression. When two
     * row value constructors are compared, their elements are paired positionally across their
     * common prefix.
     */
    private void pairs(ParseNode left, ParseNode right, Statement statement,
      Map<String, String> columns, BiConsumer<String, String> action) {
      if (
        left instanceof RowValueConstructorParseNode
          && right instanceof RowValueConstructorParseNode
      ) {
        int arity = Math.min(left.getChildren().size(), right.getChildren().size());
        for (int i = 0; i < arity; i++) {
          pairs(left.getChildren().get(i), right.getChildren().get(i), statement, columns, action);
        }
        return;
      }
      String column = column(left, columns);
      String placeholder = placeholder(right, statement);
      if (column != null && placeholder != null) {
        action.accept(column, placeholder);
      }
    }

    /**
     * Detects IN-list predicates whose placeholders cannot be bound by the decoder from a single
     * drawn row or block.
     */
    private void inLists(ParseNode where, Statement statement, boolean range) {
      for (ParseNode node : SqlRules.nodes(where)) {
        if (!(node instanceof InListParseNode)) {
          continue;
        }
        int ruled = 0;
        for (ParseNode element : node.getChildren().subList(1, node.getChildren().size())) {
          if (SqlRules.hasBind(element, statement)) {
            ruled++;
          }
        }
        if (range && ruled > 0) {
          add(Rule.PREDICATE_IN_LIST, "a " + kind + " binds only the range column, from the"
            + " first and the last row of one block, so an IN list can not hold a placeholder");
        } else if (ruled > 1) {
          add(Rule.PREDICATE_IN_LIST, ruled + " elements of an IN list hold placeholders, but one"
            + " statement binds all placeholders from one drawn row, so the elements name one row");
        }
      }
    }

    /**
     * Reports row value constructors that the decoder cannot bind, as well as equality or IN-list
     * comparisons between row value constructors of unequal arity where Phoenix compares trailing
     * elements against NULL. Inequality comparisons of unequal arity compare only the common prefix
     * and are supported by Phoenix.
     */
    private void rowValues(ParseNode where, Statement statement, boolean range) {
      boolean ruled = false;
      for (ParseNode node : SqlRules.nodes(where)) {
        if (node instanceof RowValueConstructorParseNode && SqlRules.hasBind(node, statement)) {
          ruled = true;
        }
        List<ParseNode> children = node.getChildren();
        if (
          node instanceof EqualParseNode || node instanceof NotEqualParseNode
            || node instanceof InListParseNode
        ) {
          for (int i = 1; i < children.size(); i++) {
            if (
              children.get(0) instanceof RowValueConstructorParseNode
                && children.get(i) instanceof RowValueConstructorParseNode
                && children.get(0).getChildren().size() != children.get(i).getChildren().size()
            ) {
              add(Rule.PREDICATE_ROW_VALUE,
                "a row value constructor of arity " + children.get(0).getChildren().size()
                  + " is compared for equality with one of" + " arity "
                  + children.get(i).getChildren().size() + ". Phoenix compares each"
                  + " extra element with NULL, so the condition is not the comparison as written");
            }
          }
        }
      }
      if (range && ruled) {
        add(Rule.PREDICATE_ROW_VALUE, "a " + kind + " brackets one block by the leading key"
          + " alone, so a row value constructor can not hold a placeholder");
      }
    }

    /** Classifies the bounding role played by a single range placeholder within a predicate. */
    private enum Bound {
      LOWER,
      UPPER,
      STRICT_LOWER,
      STRICT_UPPER,
      OTHER,
      NESTED
    }

    /**
     * Verifies that range placeholders form a valid block bracket. Because the decoder binds the
     * first placeholder occurrence to the block's first row and the second occurrence to its last
     * row, and because bounds compare column values directly, the first occurrence must act as an
     * inclusive lower bound and the second as an inclusive upper bound, each appearing as a
     * top-level AND conjunct.
     */
    private void rangeBounds(ParseNode where, Statement statement, Map<String, String> columns) {
      String range = statement.range();
      if (range == null) {
        // Defer reporting to the decoder's range validation, which checks overall statement shape.
        return;
      }
      List<Integer> occurrences = new ArrayList<>();
      for (Map.Entry<Integer, String> entry : statement.binds.entrySet()) {
        if (entry.getValue().equals(range)) {
          occurrences.add(entry.getKey());
        }
      }
      occurrences.sort(null);
      Bound first = bound(where, occurrences.get(0));
      Bound second = bound(where, occurrences.get(1));
      String problem = null;
      if (first == Bound.NESTED || second == Bound.NESTED) {
        problem = "a range placeholder [" + range + "] is not in a top level AND condition (it is"
          + " under OR, NOT or a function), so the bounds do not bracket the block";
      } else if (first == Bound.STRICT_LOWER || second == Bound.STRICT_UPPER) {
        problem = "a strict bound on [" + range + "] leaves out the first or the last row of the"
          + " block; use >= and <=";
      } else if (first == Bound.UPPER || first == Bound.STRICT_UPPER) {
        problem = "the first [" + range + "] is an upper bound, but the decoder binds the first"
          + " occurrence to the first row of the block and the second to the last row";
      } else if (first != Bound.LOWER || second != Bound.UPPER) {
        problem = "the first [" + range + "] must be an inclusive lower bound and the second an"
          + " inclusive upper bound, for example " + range + " >= [" + range + "] AND " + range
          + " <= [" + range + "]";
      }
      if (problem == null) {
        return;
      }
      Table table = scenario.table;
      if (
        table != null && !table.key.isEmpty() && table.key.get(0).equals(range)
          && table.order.get(0) == SortOrder.DESC
      ) {
        problem += ". The leading key " + range + " is DESC: the bounds still compare values, not"
          + " the key order";
      }
      add(Rule.PREDICATE_RANGE_BOUNDS, problem);
    }

    private static Bound bound(ParseNode where, int occurrence) {
      for (ParseNode conjunct : SqlRules.conjuncts(where)) {
        List<ParseNode> children = conjunct.getChildren();
        if (conjunct instanceof BetweenParseNode && !((BetweenParseNode) conjunct).isNegate()) {
          if (SqlRules.bind(children.get(1)) == occurrence) {
            return Bound.LOWER;
          }
          if (SqlRules.bind(children.get(2)) == occurrence) {
            return Bound.UPPER;
          }
        }
        if (!(conjunct instanceof ComparisonParseNode)) {
          continue;
        }
        // Placing the placeholder on the left side of the operator inverts the comparison direction
        // relative to placing it on the right.
        boolean right = SqlRules.bind(children.get(1)) == occurrence;
        boolean left = SqlRules.bind(children.get(0)) == occurrence;
        if (!right && !left) {
          continue;
        }
        boolean lower = conjunct instanceof GreaterThanOrEqualParseNode
          || conjunct instanceof GreaterThanParseNode;
        boolean upper =
          conjunct instanceof LessThanOrEqualParseNode || conjunct instanceof LessThanParseNode;
        boolean strict =
          conjunct instanceof GreaterThanParseNode || conjunct instanceof LessThanParseNode;
        if (left) {
          boolean swap = lower;
          lower = upper;
          upper = swap;
        }
        if (lower) {
          return strict ? Bound.STRICT_LOWER : Bound.LOWER;
        }
        if (upper) {
          return strict ? Bound.STRICT_UPPER : Bound.UPPER;
        }
        return Bound.OTHER;
      }
      return Bound.NESTED;
    }

    /**
     * Detects gaps in primary key bindings for point-read queries. Binding primary key columns on
     * both sides of an unbound key column leaves a gap that scans every value of the omitted column
     * rather than targeting only the drawn row. Because the decoder populates every placeholder
     * from the drawn row, filtering by non-key columns or by a contiguous leading key prefix is
     * valid.
     */
    private void pointKey(ParseNode where, Statement statement, Map<String, String> columns) {
      Set<String> bound = new HashSet<>();
      BiConsumer<String, String> equal = (column, placeholder) -> {
        if (column.equals(placeholder)) {
          bound.add(column);
        }
      };
      for (ParseNode conjunct : SqlRules.conjuncts(where)) {
        List<ParseNode> children = conjunct.getChildren();
        boolean in =
          conjunct instanceof InListParseNode && !((InListParseNode) conjunct).isNegate();
        if (conjunct instanceof EqualParseNode) {
          pairs(children.get(0), children.get(1), statement, columns, equal);
          pairs(children.get(1), children.get(0), statement, columns, equal);
        } else if (in) {
          // Lists containing multiple placeholders are reported separately by the IN-list rule.
          String column = column(children.get(0), columns);
          boolean all = column != null;
          for (ParseNode element : children.subList(1, children.size())) {
            all &= column != null && column.equals(placeholder(element, statement));
          }
          if (all) {
            bound.add(column);
          }
        }
      }
      List<String> key = scenario.table.key;
      int first = -1;
      int last = -1;
      for (int i = 0; i < key.size(); i++) {
        if (bound.contains(key.get(i))) {
          first = first < 0 ? i : first;
          last = i;
        }
      }
      for (int i = first + 1; i < last; i++) {
        String column = key.get(i);
        if (!bound.contains(column) && !scenario.unwritableKeys.contains(column)) {
          add(Rule.KEY_COVERAGE,
            "the point read binds primary key columns " + key.get(first) + " and " + key.get(last)
              + " but not " + column + " between them with [" + column + "], so it reads each "
              + column + " and not only the drawn row: " + sql);
        }
      }
    }

    /**
     * Invokes the index decoder's range validation using the leading primary key column from the
     * DDL and the scenario's row count.
     */
    private void decoderRange(Statement statement) {
      Table table = scenario.table;
      String leading = null;
      if (table != null && !table.key.isEmpty()) {
        leading = table.key.get(0);
      } else {
        // When no DDL is available, substitute the statement's range column for the leading key so
        // that the remaining range checks can still execute.
        leading = statement.range();
      }
      CoGenerator.Settings settings = scenario.settings;
      try {
        new IndexDecoder(scenario.rules, scenario.scenario, settings,
          settings.clusterLimit == null ? null : leading).range(sql, leading,
            scenario.scenario.getRowCount(), () -> new RowCursor.Block(0, 1));
      } catch (IllegalArgumentException | IllegalStateException e) {
        add(Rule.COGEN_RANGE, e.getMessage());
      }
    }
  }
}
