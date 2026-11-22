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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.apache.phoenix.pherf.configuration.CoGeneration;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.CoGeneration.WatermarkPolicy;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.pherf.rules.RulesApplier;

/**
 * Encapsulates the co-generation state for a single scenario within a single run, including its
 * configuration settings, its row cursor, and its decoder. The registry is keyed by the parser
 * instance and the scenario name because the writers and readers within a run receive distinct
 * Scenario objects from the same parser. Consequently, this state persists across the run so that
 * concurrent writers and readers share a common watermark. The state stores no generated keys;
 * instead, every value is derived deterministically from the row index. The scenario name must be
 * unique across all data models loaded by the parser.
 * <p>
 * A run that does not execute a data load for the scenario begins with the row range that a full
 * load of the scenario would write, initializing both the watermark and the high-water mark to the
 * configured row count. Because this initial state is an assumption rather than an observed commit,
 * the validator must confirm it. Executing a data load resets both the watermark and the high-water
 * mark to 0 first.
 */
public final class CoGenerator {
  // The registry maintains weak references to each parser. Because the stored values do not
  // reference their parser, each entry is reclaimed once its parser is garbage-collected.
  private static final Map<XMLConfigParser, Map<String, CoGenerator>> REGISTRY =
    new WeakHashMap<>();

  private final Scenario scenario;
  private final Settings settings;
  private final RulesApplier rules;
  private final RowCursor cursor;
  private IndexDecoder decoder;
  private String leadingKey;

  private CoGenerator(DataModel model, Scenario scenario, Settings settings) {
    this.scenario = scenario;
    this.settings = settings;
    this.rules = new RulesApplier(model);
    this.cursor = new RowCursor(settings.blockSize, scenario.getRowCount());
  }

  /**
   * Returns the co-generation state for a scenario configured with a coGeneration element. The
   * initial invocation for a scenario validates its configuration settings.
   * @throws IllegalArgumentException identifying the scenario and attribute of any invalid setting
   */
  public static CoGenerator of(XMLConfigParser parser, Scenario scenario) {
    synchronized (REGISTRY) {
      Map<String, CoGenerator> scenarios = REGISTRY.get(parser);
      if (scenarios == null) {
        scenarios = new HashMap<>();
        REGISTRY.put(parser, scenarios);
      }
      CoGenerator generator = scenarios.get(scenario.getName());
      if (generator == null) {
        generator = new CoGenerator(model(parser, scenario), scenario, Settings.of(scenario));
        scenarios.put(scenario.getName(), generator);
      }
      return generator;
    }
  }

  /**
   * Resolves the data model containing the specified scenario name to provide rules for the
   * decoder. Because the scenario name uniquely identifies the scenario state, encountering a
   * duplicate scenario with the same name is treated as an error.
   */
  private static DataModel model(XMLConfigParser parser, Scenario scenario) {
    DataModel found = null;
    for (DataModel model : parser.getDataModels()) {
      for (Scenario candidate : model.getScenarios()) {
        if (candidate.getName().equals(scenario.getName())) {
          if (found != null) {
            throw new IllegalArgumentException("coGeneration of scenario " + scenario.getName()
              + ": the scenario name is not unique in data models " + found.getName() + " and "
              + model.getName());
          }
          found = model;
        }
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("No data model has scenario " + scenario.getName());
    }
    return found;
  }

  public RowCursor cursor() {
    return cursor;
  }

  /**
   * Returns the index decoder for the scenario. When the scenario specifies a cluster limit, the
   * initial invocation queries the provided connection to identify the leading primary key column.
   */
  public synchronized IndexDecoder decoder(Connection connection) throws SQLException {
    if (decoder == null) {
      String leadingKey = settings.clusterLimit == null ? null : leadingKey(connection);
      decoder = new IndexDecoder(rules, scenario, settings, leadingKey);
    }
    return decoder;
  }

  /**
   * Identifies and caches the primary key column at KEY_SEQ 1 for the scenario table on first read.
   * Because a cluster limit smaller than the block size produces repeated leading column values
   * within a block, the primary key must include at least one subsequent column.
   */
  private synchronized String leadingKey(Connection connection) throws SQLException {
    if (leadingKey != null) {
      return leadingKey;
    }
    String schema = scenario.getSchemaName();
    String table = scenario.getTableNameWithoutSchemaName();
    String leading = null;
    int columns = 0;
    try (ResultSet keys = connection.getMetaData().getPrimaryKeys(null,
      schema == null ? null : schema.toUpperCase(), table.toUpperCase())) {
      while (keys.next()) {
        columns++;
        if (keys.getInt("KEY_SEQ") == 1) {
          leading = keys.getString("COLUMN_NAME");
        }
      }
    }
    String where = "clusterLimit of the coGeneration of scenario " + scenario.getName();
    if (leading == null) {
      throw new IllegalStateException(
        where + " needs a primary key, and table " + scenario.getTableName() + " has none");
    }
    if (
      columns == 1 && settings.clusterLimit != null && settings.clusterLimit < settings.blockSize
    ) {
      throw new IllegalArgumentException(where + " is less than blockSize, so rows of a block"
        + " overwrite each other: the primary key of table " + scenario.getTableName()
        + " has only column " + leading);
    }
    leadingKey = leading;
    return leading;
  }

  /**
   * Initializes a data load for the scenario by resetting both the high-water mark and the
   * watermark to 0.
   */
  public void beginLoad() {
    cursor.reset(0);
  }

  /**
   * Returns a load handle for managing the blocks of a single write workload under the configured
   * watermark policy of the scenario.
   */
  public RowCursor.Load load() {
    return cursor.load(settings.watermark);
  }

  /**
   * Creates a reader for worker w of W total workers, backed by dedicated walk sequences over both
   * individual rows and block windows.
   */
  public Reader reader(int worker, int workers) {
    long walkSeed = IndexHash.columnSeed(settings.seed, "#walk");
    return new Reader(this, new Walk(walkSeed, settings.walk, worker, workers),
      new Walk(walkSeed, settings.walk, worker, workers), worker, workers);
  }

  /** Encapsulates the co-generation settings for a scenario with all default values applied. */
  public static final class Settings {
    public final long seed;
    public final long offset;
    public final int blockSize;
    /** A null value indicates that no clustering is applied. */
    public final Integer clusterLimit;
    public final WatermarkPolicy watermark;
    public final WalkStrategy walk;

    private Settings(CoGeneration element, String where) {
      seed = parse(where, "seed", element.getSeed(), 0L, Long::parseLong);
      offset = parse(where, "offset", element.getOffset(), 0L, Long::parseLong);
      blockSize = parse(where, "blockSize", element.getBlockSize(), 1000, Integer::parseInt);
      clusterLimit =
        parse(where, "clusterLimit", element.getClusterLimit(), null, Integer::parseInt);
      watermark = parse(where, "watermark", element.getWatermark(), WatermarkPolicy.COMMIT,
        WatermarkPolicy::valueOf);
      walk =
        parse(where, "walk", element.getWalk(), WalkStrategy.MULTIPLICATIVE, WalkStrategy::valueOf);
    }

    /**
     * Parses the value of a configuration attribute string, or returns the default value when the
     * attribute is absent.
     */
    private static <T> T parse(String where, String attribute, String text, T absent,
      Function<String, T> parser) {
      if (text == null) {
        return absent;
      }
      try {
        return parser.apply(text.trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
          where + ": " + attribute + " is not a valid value: '" + text + "'", e);
      }
    }

    /**
     * Parses, validates, and returns the settings from the coGeneration element of a scenario.
     * @throws IllegalArgumentException identifying the scenario and the offending attribute
     */
    public static Settings of(Scenario scenario) {
      String where = "coGeneration of scenario " + scenario.getName();
      Settings settings = new Settings(scenario.getCoGeneration(), where);
      if (settings.offset < 0) {
        throw new IllegalArgumentException(where + ": offset is negative: " + settings.offset);
      }
      if (settings.blockSize < 1) {
        throw new IllegalArgumentException(
          where + ": blockSize is less than 1: " + settings.blockSize);
      }
      if (settings.clusterLimit != null && settings.clusterLimit < 1) {
        throw new IllegalArgumentException(
          where + ": clusterLimit is less than 1: " + settings.clusterLimit);
      }
      return settings;
    }
  }

  /**
   * Coordinates read and range operations for a single worker, where each statement draws a target
   * from the live row range [deleted, committed). Point reads draw an individual row from the
   * worker's share using one walk sequence, while scans draw a complete window from the worker's
   * share using a second walk sequence. Both walks skip entries below the delete watermark, so when
   * no deletes occur, the drawn sequence matches the underlying walk directly. Delete operations
   * claim the next committed window in order from a cursor shared across all workers.
   * <p>
   * A read or scan that completes its draw before a delete claims a window may still access rows in
   * that window while the delete executes, whereas any draw performed after the claim is guaranteed
   * never to return those rows.
   */
  public static final class Reader {
    private final CoGenerator generator;
    private final Walk walk;
    private final Walk windows;
    private final int worker;
    private final int workers;

    private Reader(CoGenerator generator, Walk walk, Walk windows, int worker, int workers) {
      this.generator = generator;
      this.walk = walk;
      this.windows = windows;
      this.worker = worker;
      this.workers = workers;
    }

    /** Binds the placeholders of a point SELECT query statement. */
    public String statement(String sql, Connection connection) throws SQLException {
      return statement(Query.Kind.SELECT, sql, connection);
    }

    /**
     * Binds the placeholders of a SQL statement of the specified kind using a single draw.
     * @throws IllegalStateException when this worker's share contains no live row or window, or
     *                               when no committed window remains available to delete
     */
    public String statement(Query.Kind kind, String sql, Connection connection)
      throws SQLException {
      IndexDecoder decoder = generator.decoder(connection);
      final RowCursor cursor = generator.cursor;
      final long rows = generator.scenario.getRowCount();
      if (kind == Query.Kind.SELECT) {
        return decoder.statement(sql,
          () -> draw(walk, cursor::deleted, cursor::watermark, "no committed row to read"));
      }
      // Ensure that the range rule does not wrap across the combined row range produced by the
      // initial data load and any concurrent writeParams writers.
      String key = generator.leadingKey(connection);
      long indexes = Math.max(rows, cursor.highWater());
      if (kind == Query.Kind.SCAN) {
        return decoder.range(sql, key, indexes, () -> cursor.window(draw(windows,
          cursor::deletedWindows, () -> cursor.windows(rows), "no committed block to scan"), rows));
      }
      return decoder.range(sql, key, indexes, () -> {
        RowCursor.Block block = cursor.claimDelete(rows);
        if (block == null) {
          throw new IllegalStateException(where() + ": no committed block is left to delete");
        }
        return block;
      });
    }

    /**
     * Draws the next value from a walk within the active interval [floor, count). The underlying
     * walk generates candidate values below count, while this method skips any values that fall
     * below floor. Both bounds are re-evaluated before each draw attempt. Before each draw, this
     * method checks that the worker's share within the range is non-empty and throws an
     * IllegalStateException when it is empty, so skipping terminates within a single pass of the
     * walk.
     */
    private long draw(Walk walk, LongSupplier floor, LongSupplier count, String none) {
      while (true) {
        long low = floor.getAsLong();
        long high = count.getAsLong();
        // Compute the smallest value belonging to this worker's share that is at least low.
        long first = low + Math.floorMod(worker - low, (long) workers);
        if (first >= high) {
          throw new IllegalStateException(
            where() + ": worker " + worker + " of " + workers + " has " + none);
        }
        long value = walk.next(high);
        if (value >= low) {
          return value;
        }
      }
    }

    private String where() {
      return "coGeneration of scenario " + generator.scenario.getName();
    }
  }
}
