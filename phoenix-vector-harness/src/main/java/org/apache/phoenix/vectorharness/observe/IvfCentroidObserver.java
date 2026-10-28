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
package org.apache.phoenix.vectorharness.observe;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.index.vector.CentroidManager;
import org.apache.phoenix.index.vector.GenerationSummary;
import org.apache.phoenix.index.vector.ScorecardRow;
import org.apache.phoenix.index.vector.VectorIndexScorecard;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The IVF supplementary observer. It reads the state that metrics do not carry: the generations of
 * each index from its catalog metadata, and the drift indicators of SYSTEM.VECTOR_CENTROID. At each
 * interval it assesses the scorecard of every live generation (posting list skew ratio, coefficient
 * of variation of list sizes, empty centroid fraction, reassignment rate) with the engine's own
 * assessment, and it records the start and the cutover of every generational rebuild as events,
 * which delimit the dual-generation query window. At stage boundaries it also counts the posting
 * lists exactly from the index table, since the scorecard lags writes.
 */
public final class IvfCentroidObserver implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(IvfCentroidObserver.class);

  private final ConnectionSupplier connections;
  private final List<String> indexes;
  private final CsvTable csv;
  private final EventLog events;
  private final Map<String, Long> activeGeneration = new HashMap<>();
  private final Map<String, Boolean> migrating = new HashMap<>();
  private final Map<String, Long> migrationStart = new HashMap<>();
  private final List<Map<String, Object>> migrations = new ArrayList<>();
  private final Object sampleLock = new Object();
  private ScheduledExecutorService scheduler;

  /** Opens connections for the observer. */
  public interface ConnectionSupplier {
    Connection get() throws SQLException;
  }

  public IvfCentroidObserver(ConnectionSupplier connections, List<String> indexes, CsvTable csv,
    EventLog events) {
    this.connections = connections;
    this.indexes = indexes;
    this.csv = csv;
    this.events = events;
  }

  public static String[] csvColumns() {
    return new String[] { "wall_ms", "offset_ms", "stage", "index", "source", "generation", "role",
      "lists", "population", "skew_ratio", "size_cv", "empty_fraction", "reassign_rate", "drifted",
      "reason" };
  }

  public synchronized void start(Duration interval) {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "vh-ivf");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleAtFixedRate(() -> {
      try {
        sample(false);
      } catch (Exception e) {
        LOG.warn("IVF observer sample failed", e);
      }
    }, 0, Math.max(100, interval.toMillis()), TimeUnit.MILLISECONDS);
  }

  /**
   * Takes one sample of every index.
   * @param exact also count the posting lists exactly from the index table
   * @return per index, the assessments of the sample
   */
  public Map<String, Object> sample(boolean exact) throws SQLException {
    synchronized (sampleLock) {
      Map<String, Object> out = new LinkedHashMap<>();
      try (Connection conn = connections.get()) {
        PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
        for (String index : indexes) {
          out.put(index, sampleIndex(pconn, index, exact));
        }
      }
      if (csv != null) {
        csv.flush();
      }
      return out;
    }
  }

  private Map<String, Object> sampleIndex(PhoenixConnection conn, String index, boolean exact)
    throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    PTable table = PhoenixRuntime.getTableNoCache(conn, index);
    Long active = table.getVectorCentroidGeneration();
    Long building = table.getVectorBuildingGeneration();
    boolean inProgress = table.isVectorRebuildInProgress();
    m.put("state", table.getIndexState() == null ? null : table.getIndexState().name());
    m.put("active_generation", active);
    m.put("building_generation", inProgress ? building : null);
    m.put("lists", table.getVectorIvfLists());
    trackTransitions(index, active, inProgress, building);
    List<Map<String, Object>> gens = new ArrayList<>();
    if (active != null) {
      gens.add(assessGeneration(conn, table, index, active, "active", exact));
    }
    if (inProgress && building != null) {
      gens.add(assessGeneration(conn, table, index, building, "building", exact));
    }
    m.put("generations", gens);
    return m;
  }

  private Map<String, Object> assessGeneration(PhoenixConnection conn, PTable table, String index,
    long generation, String role, boolean exact) throws SQLException {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("generation", generation);
    g.put("role", role);
    GenerationSummary summary = CentroidManager.loadGenerationSummary(conn, index, generation);
    if (summary != null) {
      g.put("rebuild_state", summary.getRebuildState());
      g.put("trigger_reason", summary.getTriggerReason());
      g.put("requested_lists", summary.getRequestedLists());
      g.put("last_rebuild_time", summary.getLastRebuildTime());
      g.put("last_scorecard_update", summary.getLastScorecardUpdate());
    }
    List<ScorecardRow> scorecard = CentroidManager.loadScorecard(conn, index, generation);
    VectorIndexScorecard.Assessment a =
      VectorIndexScorecard.assess(scorecard, conn.getQueryServices().getProps());
    g.put("scorecard", assessmentMap(a, scorecard.size()));
    record(index, "scorecard", generation, role, scorecard.size(), a);
    if (exact) {
      CentroidManager.Model model = CentroidManager.loadModel(conn, index, generation);
      if (model != null) {
        Map<Integer, Long> postings = VectorIndexScorecard.countPostings(conn, table);
        List<ScorecardRow> rows = new ArrayList<>();
        int first = model.getFirstId();
        for (int id = first; id < first + model.getCentroids().size(); id++) {
          Long n = postings.get(id);
          rows.add(new ScorecardRow(id, n == null ? 0 : n, 0));
        }
        VectorIndexScorecard.Assessment e =
          VectorIndexScorecard.assess(rows, conn.getQueryServices().getProps());
        g.put("exact", assessmentMap(e, rows.size()));
        record(index, "exact", generation, role, rows.size(), e);
      }
    }
    return g;
  }

  private void record(String index, String source, long generation, String role, int lists,
    VectorIndexScorecard.Assessment a) {
    if (csv == null) {
      return;
    }
    long now = System.currentTimeMillis();
    csv.row(now, events == null ? 0 : now - events.startMillis(),
      events == null ? "" : events.stage(), index, source, generation, role, lists,
      a.getPopulation(), a.getSkewRatio(), a.getSizeCv(), a.getEmptyCentroidFraction(),
      a.getReassignmentRate(), a.isDrifted(), a.getReason());
  }

  static Map<String, Object> assessmentMap(VectorIndexScorecard.Assessment a, int lists) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("lists", lists);
    m.put("population", a.getPopulation());
    m.put("skew_ratio", a.getSkewRatio());
    m.put("size_cv", a.getSizeCv());
    m.put("empty_fraction", a.getEmptyCentroidFraction());
    m.put("reassign_rate", a.getReassignmentRate());
    m.put("drifted", a.isDrifted());
    m.put("reason", a.getReason());
    return m;
  }

  private void trackTransitions(String index, Long active, boolean inProgress, Long building) {
    Long prevActive = activeGeneration.put(index, active);
    Boolean wasMigrating = migrating.put(index, inProgress);
    if (wasMigrating == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (inProgress && !wasMigrating) {
      migrationStart.put(index, now);
      event("ivf-migration-start", index + " building generation " + building);
    }
    if (!inProgress && wasMigrating) {
      Long start = migrationStart.remove(index);
      Map<String, Object> w = new LinkedHashMap<>();
      w.put("index", index);
      w.put("start_ms", start);
      w.put("end_ms", now);
      w.put("from_generation", prevActive);
      w.put("to_generation", active);
      synchronized (migrations) {
        migrations.add(w);
      }
      event("ivf-cutover", index + " generation " + prevActive + " -> " + active);
    } else if (prevActive != null && active != null && !prevActive.equals(active)) {
      event("ivf-cutover", index + " generation " + prevActive + " -> " + active);
    }
  }

  private void event(String kind, String detail) {
    if (events != null) {
      events.event(kind, detail);
    }
  }

  /** Returns the dual-generation windows observed, with their start and end times. */
  public List<Map<String, Object>> migrations() {
    synchronized (migrations) {
      return new ArrayList<>(migrations);
    }
  }

  @Override
  public synchronized void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      try {
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      scheduler = null;
    }
  }
}
